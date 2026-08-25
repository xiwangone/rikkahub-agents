package me.rerere.ai.provider.providers.reasonix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** SSE 连接状态（驱动对话顶栏状态点） */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

/**
 * Reasonix SSE 客户端 — 连接 /events 端点，实时接收服务端推送的消息流。
 *
 * 来源（2026-08-13 合规标注修正）：
 * - 基础 SSE/EventSource 模式：通用实现（早期参考 DeepSeek-Reasonix-android——该仓库无 LICENSE，
 *   已在本轮重写中剥离其独有逻辑）
 * - 热流 + turn_done 多轮收尾：自研（4ec3fb79）
 * - 断线重连/连接状态（健壮化）：吸收 Reasonix Agents（MIT）思路自行重写（c5a143fc）
 *
 * 健壮性：热流单连接多消费者；网络错误指数退避重连（1s→2s→4s…封顶 30s）；HTTP 错误不重连；
 * 连接状态经 [connectionState] 暴露（可驱动顶栏状态点）。
 *
 * 断流补拉：重连恢复（onOpen 且 attempt>0）后调用 [historyLoader] 拉取服务端 /history，
 * 对比本地已渲染的 text/reasoning 累计做差值补发（差值事件写回同一热流）——
 * 弥合断流窗口丢掉的尾部内容；无 loader 或无可补内容时安静跳过。
 */
class ReasonixSseClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val token: String = "",
    private val reconnectEnabled: Boolean = true,
    private val maxReconnectDelayMs: Long = 30_000L,
    private val historyLoader: (() -> List<HistoryMessage>)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── 断流补拉：本地已发事件累计（跨线程访问，用 lock 保护）──
    private val lock = Any()
    private val localText = StringBuilder()
    private val localReasoning = StringBuilder()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 单例热流：首次调用建立连接，后续调用复用同一事件流（重连也写回同一流） */
    private val sharedFlow: MutableSharedFlow<SseEvent> by lazy {
        val flow = MutableSharedFlow<SseEvent>(extraBufferCapacity = 256)
        startConnect(flow)
        flow
    }

    fun connect(): Flow<SseEvent> = sharedFlow.asSharedFlow()

    /**
     * 建立（或重建）SSE 连接。[attempt] 为重连次数（0=首次），用于指数退避。
     * 连接/事件写回同一 [destination] 热流——消费者无感。
     */
    private fun startConnect(destination: MutableSharedFlow<SseEvent>, attempt: Int = 0) {
        val request =
            Request.Builder()
                .url(baseUrl.toHttpUrl()!!.resolve("/events")!!)
                .header("Accept", "text/event-stream")
                .applyAuth()
                .build()

        _connectionState.value =
            if (attempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING

        val listener =
            object : EventSourceListener() {
                override fun onOpen(
                    eventSource: EventSource,
                    response: Response,
                ) {
                    _connectionState.value = ConnectionState.CONNECTED
                    // 重连恢复（非首次连接）：异步补拉 /history 差值，弥合断流窗口丢失内容
                    if (attempt > 0) {
                        Thread { runCatching { backfill(destination) } }.start()
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    try {
                        val event = json.decodeFromString<SseEvent>(data)
                        // 维护本地累计（差值补拉的去重基准）；turn_done 视为单 turn 边界，重置累计
                        when (event.kind) {
                            "text" -> event.text?.let { synchronized(lock) { localText.append(it) } }
                            "reasoning" -> event.reasoning?.let { synchronized(lock) { localReasoning.append(it) } }
                            "turn_done" -> synchronized(lock) {
                                localText.clear()
                                localReasoning.clear()
                            }
                        }
                        destination.tryEmit(event)
                    } catch (_: Exception) {
                        // 解析失败则忽略
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    val httpError = response != null && !response.isSuccessful
                    if (httpError) {
                        // HTTP 错误（401/404 等）：不重连——调用方超时/异常兜底
                        _connectionState.value = ConnectionState.DISCONNECTED
                        return
                    }
                    // 网络错误/流中断：指数退避重连（封顶 maxReconnectDelayMs）
                    _connectionState.value = ConnectionState.RECONNECTING
                    scheduleReconnect(destination, attempt)
                }

                override fun onClosed(eventSource: EventSource) {
                    // 服务端关闭：尝试重连（下一条 submit 也会触发）
                    _connectionState.value = ConnectionState.RECONNECTING
                    scheduleReconnect(destination, attempt)
                }
            }

        val factory = EventSources.createFactory(client)
        // 连接在独立线程建立，避免阻塞调用方
        Thread {
            runCatching {
                factory.newEventSource(request, listener)
            }.onFailure {
                _connectionState.value = ConnectionState.RECONNECTING
                scheduleReconnect(destination, attempt)
            }
        }.start()
    }

    /** 指数退避重连：1s→2s→4s…封顶 maxReconnectDelayMs（默认 30s） */
    private fun scheduleReconnect(destination: MutableSharedFlow<SseEvent>, attempt: Int) {
        if (!reconnectEnabled) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        val delayMs =
            (1000L shl attempt.coerceAtMost(30).toInt())
                .coerceAtMost(maxReconnectDelayMs.coerceAtLeast(1000L))
        Thread {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            startConnect(destination, attempt + 1)
        }.start()
    }

    /**
     * 断流补拉：取 /history 最后一条非空 assistant 消息，与本地已渲染累计
     * 做前缀差值比较——服务端已生成而本地未收到的尾部文本/推理补发为差值事件。
     * 前缀匹配失败（本地累计已被 turn_done 重置、或内容错位）时不补，保证不重复渲染。
     */
    private fun backfill(destination: MutableSharedFlow<SseEvent>) {
        val loader = historyLoader ?: return
        val history = runCatching { loader() }.getOrNull() ?: return
        val last =
            history.lastOrNull { h ->
                h.role == "assistant" && (!h.content.isNullOrBlank() || !h.reasoning.isNullOrBlank())
            } ?: return

        val hText = last.content ?: ""
        val hReasoning = last.reasoning ?: ""

        // 快照本地累计（跨线程用 lock 保护）
        val (locText, locReasoning) =
            synchronized(lock) { localText.toString() to localReasoning.toString() }

        // text 差值补发：history 完整文本以本地累计为前缀且更长 → 缺失尾部补发。
        // 二次确认：快照后无新事件推进累计才补，避免与实时流内容重叠造成重复渲染。
        val textDiff =
            synchronized(lock) {
                val cur = localText.toString()
                if (cur != locText) {
                    null
                } else if (hText.length > cur.length && hText.startsWith(cur)) {
                    hText.substring(cur.length).also { localText.append(it) }
                } else {
                    null
                }
            }
        if (!textDiff.isNullOrEmpty()) {
            destination.tryEmit(SseEvent(kind = "text", text = textDiff))
        }

        // reasoning 差值补发（同理）
        val reasoningDiff =
            synchronized(lock) {
                val cur = localReasoning.toString()
                if (cur != locReasoning) {
                    null
                } else if (hReasoning.length > cur.length && hReasoning.startsWith(cur)) {
                    hReasoning.substring(cur.length).also { localReasoning.append(it) }
                } else {
                    null
                }
            }
        if (!reasoningDiff.isNullOrEmpty()) {
            destination.tryEmit(SseEvent(kind = "reasoning", reasoning = reasoningDiff))
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        } else if (username.isNotBlank() || password.isNotBlank()) {
            header("Authorization", okhttp3.Credentials.basic(username, password))
        }
        return this
    }
}
