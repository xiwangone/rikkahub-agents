package me.rerere.ai.provider.providers.backend

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HTTP 错误静默断连。
 *
 * 服务端返回 HTTP 401 时，[BackendSseClient] 的 onFailure 只把
 * connectionState 置为 DISCONNECTED 就 return：事件流既不抛错、也不发
 * 错误事件、也不结束，消费方永远等不到后续内容，也等不到失败——
 * 回复被截断且无用量，UI 上看起来只是"没反应"。
 *
 * 正确行为：401 必须传播到事件流的消费方——或以错误事件送达，
 * 或让事件流以异常终止（流直接结束亦可接受为弱信号：
 * 消费方至少能感知流已结束，再由 Processor 层以错误而非假 Finish 收尾）。
 *
 * 实现：用 OkHttp 拦截器直接返回 401 响应，不依赖 MockWebServer
 * （不在测试依赖里），也不需要真实服务端；拦截器在网络层之前短路，
 * baseUrl 用不可达地址也不会产生真实连接。
 */
class BackendSseClientErrorTest {
    private fun clientReturningHttp(code: Int): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("Unauthorized")
                        .body("".toResponseBody(null))
                        .build()
                }
            )
            .build()

    /** 等待客户端观测到 401（connectionState 变为 DISCONNECTED），超时返回 false。 */
    private suspend fun awaitDisconnected(
        sse: BackendSseClient,
        timeoutMs: Long = 10_000,
    ): Boolean =
        withTimeoutOrNull(timeoutMs) {
            sse.connectionState.first { it == ConnectionState.DISCONNECTED }
            true
        } ?: false

    @Test
    fun http401IsObservedAsDisconnected() =
        runBlocking {
            // 对照：401 确实到达了客户端（状态翻转为 DISCONNECTED）。
            // 本测试在 bug 修复前后都应通过；它只证明下面的主测试的前提成立，
            // 不证明错误有传播——状态翻转本身正是"静默"的那一半。
            val sse =
                BackendSseClient(
                    baseUrl = "http://127.0.0.1:9",
                    client = clientReturningHttp(401),
                )
            try {
                sse.connect()
                assertTrue("客户端应在 401 后到达 DISCONNECTED", awaitDisconnected(sse))
            } finally {
                sse.close()
            }
        }

    @Test
    fun http401MustSurfaceErrorInsteadOfSilentDisconnect() =
        runBlocking {
            val sse =
                BackendSseClient(
                    baseUrl = "http://127.0.0.1:9",
                    client = clientReturningHttp(401),
                )
            try {
                val events = sse.connect()
                // 先订阅再等状态：错误事件（若修复后发出）可能在 onFailure 里
                // 立即写入无 replay 的热流，订阅晚了就会丢事件。
                val errorOrEnd =
                    async {
                        runCatching {
                            withTimeout(8_000) {
                                events.first { it.kind == "error" || !it.err.isNullOrBlank() }
                            }
                        }
                    }
                assertTrue(
                    "前提不成立：10s 内客户端未到达 DISCONNECTED，401 未被观测到",
                    awaitDisconnected(sse),
                )

                val outcome = errorOrEnd.await()
                val error = outcome.exceptionOrNull()
                when {
                    outcome.isSuccess -> Unit // 错误事件已送达消费方
                    error is NoSuchElementException -> Unit // 流已终止，不再静默挂起
                    error is TimeoutCancellationException ->
                        fail(
                            "HTTP 401 后 8s 内事件流既无错误事件、也不结束、不抛错：" +
                                "静默断连，消费方永远等不到失败"
                        )
                    error != null -> Unit // 流以异常失败，错误已传播
                    else -> fail("未预期的结果：$outcome")
                }
            } finally {
                sse.close()
            }
        }
}
