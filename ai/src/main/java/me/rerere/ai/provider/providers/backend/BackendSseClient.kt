package me.rerere.ai.provider.providers.backend

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
 * Backend SSE 客户端 — 连接 /events 端点，实时接收服务端推送的消息流。
 *
 * 实现要点：
 * - 基础 SSE / EventSource 模式
 * - 热流 + turn_done 多轮收尾
 * - 断线重连与连接状态（指数退避）
 * - [close] 主动关闭连接并停止重连，避免回合结束后后台线程泄漏与多会话串流
 */
class BackendSseClient(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val token: String = "",
    private val reconnectEnabled: Boolean = true,
    private val maxReconnectDelayMs: Long = 30_000L,
    private val historyLoader: (() -> List<HistoryMessage>)? = null,
    /** 服务端状态读取（用于断流重连后探测是否有待应答交互） */
    private val statusLoader: (() -> StatusInfo?)? = null,
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

    @Volatile
    private var closed = false

    @Volatile
    private var currentEventSource: EventSource? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 单例热流：首次调用建立连接，后续调用复用同一事件流（重连也写回同一流） */
    private val sharedFlow: MutableSharedFlow<SseEvent> by lazy {
        // extraBufferCapacity 必须远大于峰值吞吐：无 replay 的热流在订阅建立前
        // （connect() → 消费方开始收集之间有网络往返）tryEmit 会静默丢事件。
        // 发射端在 OkHttp 线程，不能挂起 emit，只能靠大缓冲 + 失败日志兜底。
        val flow = MutableSharedFlow<SseEvent>(
            extraBufferCapacity = 4096,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
        )
        startConnect(flow)
        flow
    }

    fun connect(): Flow<SseEvent> = sharedFlow.asSharedFlow()

    /** 主动关闭 SSE 连接并禁止后续自动重连。 */
    fun close() {
        closed = true
        _connectionState.value = ConnectionState.DISCONNECTED
        runCatching { currentEventSource?.cancel() }
        currentEventSource = null
    }

    /**
     * 建立（或重建）SSE 连接。[attempt] 为重连次数（0=首次），用于指数退避。
     * 连接/事件写回同一 [destination] 热流——消费者无感。
     */
    private fun startConnect(destination: MutableSharedFlow<SseEvent>, attempt: Int = 0) {
        if (closed) return
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
                    if (closed) {
                        runCatching { eventSource.cancel() }
                        return
                    }
                    _connectionState.value = ConnectionState.CONNECTED
                    if (attempt > 0) {
                        Thread {
                            runCatching { backfill(destination) }.onFailure {
                                android.util.Log.w("BackendSse", "断流补拉失败", it)
                            }
                        }.start()
                        Thread {
                            runCatching {
                                if (statusLoader?.invoke()?.pendingPrompt == true) {
                                    destination.tryEmit(
                                        SseEvent(
                                            kind = "notice",
                                            text = "服务端仍在等待应答（审批 / 提问）：请在通知栏处理，或重启服务后重试",
                                            level = "warn",
                                        ),
                                    )
                                }
                            }
                        }.start()
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (closed) return
                    try {
                        val event = json.decodeFromString<SseEvent>(data)
                        when (event.kind) {
                            "text" -> event.text?.let { synchronized(lock) { localText.append(it) } }
                            "reasoning" ->
                                (event.reasoning ?: event.text)?.let {
                                    synchronized(lock) { localReasoning.append(it) }
                                }
                            "turn_done" -> synchronized(lock) {
                                localText.clear()
                                localReasoning.clear()
                            }
                        }
                        if (!destination.tryEmit(event)) {
                            android.util.Log.w("BackendSse", "SSE 缓冲已满，事件被丢弃（kind=" + event.kind + "）")
                        }
                    } catch (_: Exception) {
                        // 解析失败则忽略
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (closed) return
                    val httpError = response != null && !response.isSuccessful
                    if (httpError) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        return
                    }
                    _connectionState.value = ConnectionState.RECONNECTING
                    scheduleReconnect(destination, attempt)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (closed) return
                    _connectionState.value = ConnectionState.RECONNECTING
                    scheduleReconnect(destination, attempt)
                }
            }

        val factory = EventSources.createFactory(client)
        Thread {
            if (closed) return@Thread
            runCatching {
                val es = factory.newEventSource(request, listener)
                currentEventSource = es
                if (closed) es.cancel()
            }.onFailure {
                if (!closed) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    scheduleReconnect(destination, attempt)
                }
            }
        }.start()
    }

    /** 指数退避重连：1s→2s→4s…封顶 maxReconnectDelayMs（默认 30s） */
    private fun scheduleReconnect(destination: MutableSharedFlow<SseEvent>, attempt: Int) {
        if (closed || !reconnectEnabled) {
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
            if (!closed) {
                startConnect(destination, attempt + 1)
            }
        }.start()
    }

    private fun backfill(destination: MutableSharedFlow<SseEvent>) {
        if (closed) return
        val loader = historyLoader ?: return
        val history = runCatching { loader() }.getOrNull() ?: return
        val last =
            history.lastOrNull { h ->
                h.role == "assistant" && (!h.content.isNullOrBlank() || !h.reasoning.isNullOrBlank())
            } ?: return

        val hText = last.content ?: ""
        val hReasoning = last.reasoning ?: ""

        val (locText, locReasoning) =
            synchronized(lock) { localText.toString() to localReasoning.toString() }

        val textDiff =
            synchronized(lock) {
                val cur = localText.toString()
                if (cur != locText) {
                    null
                } else {
                    computeBackfillDiff(hText, cur)?.also { localText.append(it) }
                }
            }
        if (!textDiff.isNullOrEmpty()) {
            destination.tryEmit(SseEvent(kind = "text", text = textDiff))
        }

        val reasoningDiff =
            synchronized(lock) {
                val cur = localReasoning.toString()
                if (cur != locReasoning) {
                    null
                } else {
                    computeBackfillDiff(hReasoning, cur)?.also { localReasoning.append(it) }
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

internal fun computeBackfillDiff(full: String, local: String): String? {
    if (full.isBlank()) return null
    if (local.isEmpty()) return full
    if (full.length <= local.length) return null
    val pos = full.lastIndexOf(local)
    if (pos < 0) return null
    return full.substring(pos + local.length).ifEmpty { null }
}
