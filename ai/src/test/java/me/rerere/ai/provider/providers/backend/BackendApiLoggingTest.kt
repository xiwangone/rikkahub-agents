package me.rerere.ai.provider.providers.backend

import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.common.log.AppLogger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 批 2 框架收敛：backend 的直接 Log.* 已改 AppLogger。
 * 用录制 sink 证明改过的调用点真的走了 AppLogger，而不是只换了名字。
 */
class BackendApiLoggingTest {

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(
                tag: String,
                message: String,
            ) {
                recorded += Triple('D', tag, message)
            }

            override fun i(
                tag: String,
                message: String,
            ) {
                recorded += Triple('I', tag, message)
            }

            override fun w(
                tag: String,
                message: String,
            ) {
                recorded += Triple('W', tag, message)
            }

            override fun w(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                recorded += Triple('W', tag, message)
            }

            override fun e(
                tag: String,
                message: String,
            ) {
                recorded += Triple('E', tag, message)
            }

            override fun e(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                recorded += Triple('E', tag, message)
            }
        }

    // AppLogger 没有卸载接口；测试后装一个直通 logcat 的 sink，让同 JVM 内
    // 后续测试的 AppLogger 调用恢复默认行为（只记录、不干预）。
    private val logcatSink =
        object : AppLogger.Sink {
            override fun d(
                tag: String,
                message: String,
            ) {
                Log.d(tag, message)
            }

            override fun i(
                tag: String,
                message: String,
            ) {
                Log.i(tag, message)
            }

            override fun w(
                tag: String,
                message: String,
            ) {
                Log.w(tag, message)
            }

            override fun w(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                Log.w(tag, message, tr)
            }

            override fun e(
                tag: String,
                message: String,
            ) {
                Log.e(tag, message)
            }

            override fun e(
                tag: String,
                message: String,
                tr: Throwable?,
            ) {
                Log.e(tag, message, tr)
            }
        }

    @Before
    fun installRecordingSink() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun restoreLogcatSink() {
        AppLogger.install(logcatSink)
    }

    private fun clientReturningBody(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody(null))
                        .build()
                },
            ).build()

    @Test
    fun `getHistory parse failure routes through AppLogger as error`() =
        runBlocking {
            // 服务端返回无法解析的 JSON，走 getOrElse 分支。
            // 带 throwable 的 w 按批 2 级别口径（带异常的 w 转 e）应记为 e。
            val api =
                BackendApi(
                    baseUrl = "http://127.0.0.1:1",
                    client = clientReturningBody("not-json{{{"),
                )
            val history = api.getHistory()
            assertTrue(history.isEmpty())
            assertTrue(
                recorded.any { (level, tag, message) ->
                    level == 'E' && tag == "BackendApi" && message.contains("/history")
                },
            )
        }
}
