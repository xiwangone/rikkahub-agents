package me.rerere.ai.util

import me.rerere.common.log.AppLogger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.stripBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.internal.ServerSentEventReader
import java.io.IOException

private const val TAG = "SSE"

class SSEEventSource(
    private val request: Request,
    private val listener: EventSourceListener,
) : EventSource,
    ServerSentEventReader.Callback,
    Callback {
    private var call: Call? = null

    @Volatile
    private var canceled = false

    /** 首响应与最后一条事件的墙钟，用于区分「刚收到数据就被取消」与「长时间无数据后超时」。 */
    @Volatile
    private var openedAtMs = 0L

    @Volatile
    private var lastEventAtMs = 0L

    fun connect(callFactory: Call.Factory) {
        call =
            callFactory.newCall(request).apply {
                enqueue(this@SSEEventSource)
            }
    }

    override fun onResponse(
        call: Call,
        response: Response,
    ) {
        processResponse(response)
    }

    fun processResponse(response: Response) {
        openedAtMs = System.currentTimeMillis()
        response.use {
            if (!response.isSuccessful) {
                listener.onFailure(this, null, response)
                return
            }

            val body = response.body

            if (!body.isEventStream()) {
                listener.onFailure(
                    this,
                    IllegalStateException("Invalid content-type: ${body.contentType()}"),
                    response,
                )
                return
            }

            // This is a long-lived response. Cancel full-call timeouts.
            call?.timeout()?.cancel()

            // Replace the body with a stripped one so the callbacks can't see real data.
            val response = response.stripBody()

            val reader = ServerSentEventReader(body.source(), this)
            try {
                if (!canceled) {
                    listener.onOpen(this, response)
                    while (!canceled && reader.processNextEvent()) {
                    }
                }
            } catch (e: Exception) {
                // 断流归因：canceled=true 只说明「本地调了 cancel()」，真实诱因往往在取消方；
                // 这里连同异常类型与「距最后一条事件多久」一起记，能分开「刚有数据就被切」与「静默等待很久」。
                AppLogger.w(
                    TAG,
                    "stream interrupted: canceled=$canceled cause=${e.javaClass.simpleName}:${e.message} ${eventGap()}",
                )
                val exception =
                    when {
                        canceled -> IOException("canceled", e)
                        else -> e
                    }
                listener.onFailure(this, exception, response)
                return
            }
            if (canceled) {
                AppLogger.w(TAG, "stream ended by cancel() (no exception) ${eventGap()}")
                listener.onFailure(this, IOException("canceled"), response)
            } else {
                listener.onClosed(this)
            }
        }
    }

    private fun ResponseBody.isEventStream(): Boolean {
        val contentType = contentType() ?: return false
        return contentType.type == "text" && contentType.subtype == "event-stream"
    }

    override fun onFailure(
        call: Call,
        e: IOException,
    ) {
        listener.onFailure(this, e, null)
    }

    override fun request(): Request = request

    override fun cancel() {
        canceled = true
        // 记录取消方：本地 cancel() 与中继重置此前在日志里完全同形（都是 IOException("canceled")），
        // 没有这一行就无法得知是谁掐断的流。
        AppLogger.w(
            TAG,
            "cancel(): caller=${cancelCaller()} thread=${Thread.currentThread().name} ${eventGap()}",
        )
        call?.cancel()
    }

    /** 取 9 层当前线程栈（去掉栈自身与 cancel 两层）：收尾、超时、用户停止的栈在更上层才分得开。 */
    private fun cancelCaller(): String =
        Thread.currentThread()
            .stackTrace
            .drop(2)
            .take(9)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    private fun eventGap(): String {
        val now = System.currentTimeMillis()
        return if (lastEventAtMs == 0L) {
            "no event yet (opened ${now - openedAtMs}ms ago)"
        } else {
            "last event ${now - lastEventAtMs}ms ago"
        }
    }

    override fun onEvent(
        id: String?,
        type: String?,
        data: String,
    ) {
        lastEventAtMs = System.currentTimeMillis()
        listener.onEvent(this, id, type, data)
    }

    override fun onRetryChange(timeMs: Long) {
        // Ignored. We do not auto-retry.
    }

    companion object {
        fun factory(callFactory: Call.Factory) = EventSource.Factory { request, listener ->
            val actualRequest =
                if (request.header("Accept") == null) {
                    request.newBuilder().addHeader("Accept", "text/event-stream").build()
                } else {
                    request
                }

            SSEEventSource(actualRequest, listener).apply {
                connect(callFactory)
            }
        }
    }
}
