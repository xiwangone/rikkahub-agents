package me.rerere.ai.provider.providers.google

import android.util.Log
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.log.AppLogger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// 生产侧的 TAG 是 file-private，测试文件不可见：此处自备同值常量。
private const val TAG = "InteractionsAPI"

/**
 * 批 4（降噪去重）降-P1-1：`onFailure` 补 1 条汇总 e。
 *
 * 旧行为：本站的 `onFailure` 成功路径（错误体可解析）不记任何行，流失败在
 * 日志里完全静默；只有解析失败时记 1 条 e。
 * 新行为：一次流失败留 1 条 e（HTTP 状态 + 最终异常摘要），与其余 provider
 * 一致；解析失败时汇总行里只留一句，不单独记一条。
 *
 * 用录制 sink 直接驱动 [InteractionsAPI.streamText]（internal 类，同模块
 * 测试可访问）：拦截器对所有请求返回 HTTP 500。
 */
class InteractionsAPIOnFailureLoggingTest {

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
    fun installRecordingSink() {
        recorded.clear()
        AppLogger.install(recordingSink)
    }

    @After
    fun restoreLogcatSink() {
        AppLogger.install(logcatSink)
    }

    // okhttp 的 toResponseBody 造的是匿名子类，生产侧 stringSafe() 只认
    // RealResponseBody：直接构造 RealResponseBody（okhttp 5.4.0 构造器为
    // (contentType: String?, contentLength: Long, source: BufferedSource)）。
    private fun realResponseBody(body: String): ResponseBody {
        val source = Buffer().writeUtf8(body)
        return RealResponseBody(null, source.size, source)
    }

    private fun clientReturningBody(body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Internal Server Error")
                    .body(realResponseBody(body))
                    .build()
            }
            .build()

    private fun streamTextWith(client: OkHttpClient) = runBlocking {
        val api = InteractionsAPI(client, KeyRoulette.default())
        runCatching {
            api.streamText(
                providerSetting = ProviderSetting.Google(
                    apiKey = "fixture-key-not-a-credential",
                    useInteractionsApi = true,
                ),
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "gemini-test")),
            ).toList()
        }
    }

    private fun onFailureLines() =
        recorded.filter { it.second == TAG && it.third.startsWith("onFailure:") }

    @Test
    fun `parsable json error body produces exactly one e line with the parsed message`() {
        val body = """{"error":{"message":"boom-test-marker"}}"""
        val result = streamTextWith(clientReturningBody(body))
        // NB: never assert inside a try/catch(Throwable) here — fail() throws
        // AssertionError, which catch(Throwable) would swallow and fake a pass.
        assertTrue("streamText must fail on HTTP 500, but it completed", result.isFailure)

        val lines = onFailureLines()
        assertEquals("one stream failure must log exactly one onFailure line, got $lines", 1, lines.size)
        assertEquals('E', lines[0].first)
        assertTrue("summary must start with HTTP status, got: ${lines[0].third}", lines[0].third.startsWith("onFailure: HTTP 500:"))
        assertTrue("summary must carry the parsed error message, got: ${lines[0].third}", lines[0].third.contains("boom-test-marker"))
        assertTrue("no line may contain the full error body", recorded.none { it.third.contains(body) })
    }

    @Test
    fun `empty error body produces exactly one e line`() {
        val result = streamTextWith(clientReturningBody(""))
        assertTrue("streamText must fail on HTTP 500, but it completed", result.isFailure)

        val lines = onFailureLines()
        assertEquals("one stream failure must log exactly one onFailure line, got $lines", 1, lines.size)
        assertEquals('E', lines[0].first)
        assertTrue("summary must start with HTTP status, got: ${lines[0].third}", lines[0].third.startsWith("onFailure: HTTP 500:"))
        assertTrue("summary must fall back to Unknown error, got: ${lines[0].third}", lines[0].third.contains("Unknown error: 500"))
    }

    @Test
    fun `unparsable error body produces exactly one e line without the raw body`() {
        // 截断的 JSON 对象才是真不可解析："<html>..." 这类纯文本会被解析器当作裸字面量接受。
        val body = "{\"boom-test-raw\":"
        val result = streamTextWith(clientReturningBody(body))
        assertTrue("streamText must fail on HTTP 500, but it completed", result.isFailure)

        val lines = onFailureLines()
        assertEquals("one stream failure must log exactly one onFailure line, got $lines", 1, lines.size)
        assertTrue("summary must mark the body unparseable, got: ${lines[0].third}", lines[0].third.contains("error body unparseable"))
        assertTrue("no line may contain the raw error body", recorded.none { it.third.contains("boom-test-raw") })
    }
}
