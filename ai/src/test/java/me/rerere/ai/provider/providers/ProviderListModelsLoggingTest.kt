package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.common.log.AppLogger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 批 3 覆-P0-4：三大 provider 的 listModels 失败记日志。
 *
 * 用 OkHttp 拦截器短路返回 canned 响应，不依赖真实服务端；断言失败时 AppLogger
 * 记一条 e，含供应商名、脱敏后的 host（只有 host，不含 path/query）、HTTP 状态码
 * 与截断后的错误摘要。
 */
class ProviderListModelsLoggingTest {

    private val recorded = mutableListOf<Triple<Char, String, String>>()

    private val recordingSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                recorded += Triple('D', tag, message)
            }

            override fun i(tag: String, message: String) {
                recorded += Triple('I', tag, message)
            }

            override fun w(tag: String, message: String) {
                recorded += Triple('W', tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('W', tag, message)
            }

            override fun e(tag: String, message: String) {
                recorded += Triple('E', tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                recorded += Triple('E', tag, message)
            }
        }

    // AppLogger 没有卸载接口；测试后装一个直通 logcat 的 sink，让同 JVM 内
    // 后续测试的 AppLogger 调用恢复默认行为（只记录、不干预）。
    private val logcatSink =
        object : AppLogger.Sink {
            override fun d(tag: String, message: String) {
                Log.d(tag, message)
            }

            override fun i(tag: String, message: String) {
                Log.i(tag, message)
            }

            override fun w(tag: String, message: String) {
                Log.w(tag, message)
            }

            override fun w(tag: String, message: String, tr: Throwable?) {
                Log.w(tag, message, tr)
            }

            override fun e(tag: String, message: String) {
                Log.e(tag, message)
            }

            override fun e(tag: String, message: String, tr: Throwable?) {
                Log.e(tag, message, tr)
            }
        }

    @Before
    fun setUp() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun tearDown() {
        AppLogger.install(logcatSink)
    }

    private fun clientReturning(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(body.toResponseBody(null))
                        .build()
                },
            )
            .build()

    private fun assertListModelsFailureLogged(tag: String, code: Int, detail: String? = null) {
        assertTrue(
            "expected an E log from $tag with code=$code, got: $recorded",
            recorded.any { (level, entryTag, message) ->
                level == 'E' && entryTag == tag &&
                    "listModels failed" in message &&
                    "host=127.0.0.1" in message &&
                    "code=$code" in message &&
                    (detail == null || detail in message)
            },
        )
    }

    @Test
    fun `openai listModels non-200 logs e`() = runBlocking {
        val provider = OpenAIProvider(
            clientReturning(500, """{"error":{"message":"quota exceeded"}}"""),
        )
        try {
            provider.listModels(ProviderSetting.OpenAI(apiKey = "test", baseUrl = "http://127.0.0.1:9/v1"))
            fail("expected listModels to throw")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertListModelsFailureLogged("OpenAIProvider", 500, "quota exceeded")
    }

    @Test
    fun `openai listModels 200 without data logs e`() = runBlocking {
        val provider = OpenAIProvider(clientReturning(200, """{"data":null}"""))
        try {
            provider.listModels(ProviderSetting.OpenAI(apiKey = "test", baseUrl = "http://127.0.0.1:9/v1"))
            fail("expected listModels to throw")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertListModelsFailureLogged("OpenAIProvider", 200, "no `data` field")
    }

    @Test
    fun `claude listModels non-200 logs e`() = runBlocking {
        val provider = ClaudeProvider(
            clientReturning(500, """{"error":{"message":"overloaded"}}"""),
        )
        try {
            provider.listModels(ProviderSetting.Claude(apiKey = "test", baseUrl = "http://127.0.0.1:9/v1"))
            fail("expected listModels to throw")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertListModelsFailureLogged("ClaudeProvider", 500, "overloaded")
    }

    @Test
    fun `google listModels non-200 logs e with page`() = runBlocking {
        val provider = GoogleProvider(
            clientReturning(500, """{"error":{"message":"backend error"}}"""),
        )
        try {
            provider.listModels(
                ProviderSetting.Google(apiKey = "test", baseUrl = "http://127.0.0.1:9/v1beta"),
            )
            fail("expected listModels to throw")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertTrue(
            "expected an E log from GoogleProvider with code=500 page=1, got: $recorded",
            recorded.any { (level, entryTag, message) ->
                level == 'E' && entryTag == "GoogleProvider" &&
                    "listModels failed" in message &&
                    "host=127.0.0.1" in message &&
                    "code=500" in message && "page=1" in message
            },
        )
    }
}
