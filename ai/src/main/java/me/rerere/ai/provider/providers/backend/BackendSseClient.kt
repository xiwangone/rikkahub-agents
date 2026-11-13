package me.rerere.ai.provider.providers.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import me.rerere.common.android.SafeRequestLoggingInterceptor
import me.rerere.common.log.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** 本地累计文本 / 思考内容的上限（字符数），见 BackendSseClient 的补拉缓冲。 */
private const val MAX_LOCAL_BUFFER_CHARS = 1_000_000

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
// 连接参数（地址/凭据/重连策略/状态与历史加载器/客户端）聚在一处，拆开反而割裂建连语义。
@Suppress("LongParameterList")
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
        // 有限读超时：服务端静默挂起时连接最终会报错走重连，而不是无限等待。
        // 取 10 分钟，长于事件处理器的 300 秒首内容超时，不会先于它触发。
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        // 最小请求日志：只记 host + 状态码 + 耗时，不记 path/query/headers/body
        // （SSE URL 后的路径含会话信息，:common 层无脱敏能力）。
        .addInterceptor(SafeRequestLoggingInterceptor())
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── 断流补拉：本地已发事件累计（跨线程访问，用 lock 保护）──
    // 只用于断流后与服务端历史做差分补发，超限后丢弃最旧内容，避免服务端
    // 迟迟不发 turn_done 时无界增长。
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
        // replay=1：保留最新一个事件，供订阅到达晚于发射的消费方补收——
        // 终端错误事件（HTTP 401 等）常在 connect() 后、消费方开始收集前就已发出，
        // replay=0 时 tryEmit 返回 true 也不会留给后到的订阅者，错误会静默丢失。
        // extraBufferCapacity 仍远大于峰值吞吐：发射端在 OkHttp 线程，不能挂起
        // emit，订阅者消费慢时靠大缓冲 + 失败日志兜底。
        val flow = MutableSharedFlow<SseEvent>(
            replay = 1,
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

    /** 有上限的追加：超限时丢弃最旧内容，只保留尾部。 */
    private fun appendCapped(builder: StringBuilder, text: String) {
        builder.append(text)
        if (builder.length > MAX_LOCAL_BUFFER_CHARS) {
            builder.delete(0, builder.length - MAX_LOCAL_BUFFER_CHARS)
        }
    }

    /** 守护线程：进程退出时不被重连 / 补拉线程拖住，且带名字便于排查。 */
    private fun daemonThread(name: String, block: () -> Unit) {
        Thread({ block() }, name).apply { isDaemon = true }.start()
    }

    /**
     * 建立（或重建）SSE 连接。[attempt] 为重连次数（0=首次），用于指数退避。
     * 连接/事件写回同一 [destination] 热流——消费者无感。
     */
    private fun startConnect(destination: MutableSharedFlow<SseEvent>, attempt: Int = 0) {
        if (closed) return
        val request =
            Request.Builder()
                .url(
                    baseUrl.toHttpUrlOrNull()?.resolve("/events")
                        ?: throw IllegalArgumentException("Invalid Backend baseUrl: '$baseUrl'")
                )
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
                        daemonThread("BackendSse-backfill") {
                            runCatching { backfill(destination) }.onFailure {
                                AppLogger.e("BackendSse", "断流补拉失败", it)
                            }
                        }
                        daemonThread("BackendSse-status-notice") {
                            runCatching {
                                if (statusLoader?.invoke()?.pendingPrompt == true) {
                                    val notice =
                                        SseEvent(
                                            kind = "notice",
                                            text = "The server is still waiting for a response (approval / question): please handle it in the notification shade, or restart the service and retry",
                                            level = "warn",
                                        )
                                    if (!destination.tryEmit(notice)) {
                                        AppLogger.w("BackendSse", "SSE 缓冲已满，事件被丢弃（kind=notice）")
                                    }
                                }
                            }
                        }
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
                            "text" -> event.text?.let { synchronized(lock) { appendCapped(localText, it) } }
                            "reasoning" ->
                                (event.reasoning ?: event.text)?.let {
                                    synchronized(lock) { appendCapped(localReasoning, it) }
                                }
                            "turn_done" -> synchronized(lock) {
                                localText.clear()
                                localReasoning.clear()
                            }
                        }
                        if (!destination.tryEmit(event)) {
                            AppLogger.w("BackendSse", "SSE 缓冲已满，事件被丢弃（kind=" + event.kind + "）")
                        }
                    } catch (e: Exception) {
                        AppLogger.e("BackendSse", "事件 JSON 解析失败，丢弃该事件：${data.take(200)}", e)
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
                        // HTTP 错误不重连（重连会重复失败），改为向事件流发一个 error 事件传播。
                        // 选了「发错误事件」而非「让流异常终止」：事件流是热共享流，没有
                        // 可终止的末端状态，发错误事件是唯一的传播形态；消费方收到
                        // error 事件后立即以异常收尾，不再静默等待。
                        _connectionState.value = ConnectionState.DISCONNECTED
                        val status = response?.code ?: 0
                        val errorEvent =
                            SseEvent(
                                kind = "error",
                                code = status.toString(),
                                err = "HTTP $status ${response?.message?.ifBlank { "request failed" }}",
                            )
                        if (!destination.tryEmit(errorEvent)) {
                            AppLogger.w("BackendSse", "SSE 缓冲已满，错误事件被丢弃（HTTP $status）")
                        }
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
        daemonThread("BackendSse-connect") {
            if (closed) return@daemonThread
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
        }
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
        daemonThread("BackendSse-reconnect") {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return@daemonThread
            }
            if (!closed) {
                startConnect(destination, attempt + 1)
            }
        }
    }

    private fun backfill(destination: MutableSharedFlow<SseEvent>) {
        if (closed) return
        val loader = historyLoader ?: return
        val history = runCatching { loader() }
            .onFailure { AppLogger.e("BackendSse", "断流补拉 loader 失败", it) }
            .getOrNull() ?: return
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
            if (!destination.tryEmit(SseEvent(kind = "text", text = textDiff))) {
                AppLogger.w("BackendSse", "SSE 缓冲已满，事件被丢弃（kind=text, len=" + textDiff.length + "）")
            }
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
            if (!destination.tryEmit(SseEvent(kind = "reasoning", reasoning = reasoningDiff))) {
                AppLogger.w("BackendSse", "SSE 缓冲已满，事件被丢弃（kind=reasoning, len=" + reasoningDiff.length + "）")
            }
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        if (!isAuthSafeTarget()) {
            AppLogger.w(
                "BackendSse",
                "baseUrl 未使用 https 且非本机地址，不发送 Authorization，避免凭据走明文 HTTP",
            )
            return this
        }
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        } else if (username.isNotBlank() || password.isNotBlank()) {
            header("Authorization", okhttp3.Credentials.basic(username, password))
        }
        return this
    }

    /** https 始终可发凭据；http 只允许本机回环地址，局域网/公网 http 一律不发凭据。 */
    private fun isAuthSafeTarget(): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        if (url.isHttps) return true
        return url.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
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
