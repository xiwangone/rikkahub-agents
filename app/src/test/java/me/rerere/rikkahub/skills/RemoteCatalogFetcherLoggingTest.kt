package me.rerere.rikkahub.skills

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * 批 5 (级别与 tag) 级-P1-2: [RemoteCatalogFetcher] 的日志级别。
 *
 * - 取数失败 (URL 不可用、请求抛错、非 200) 是本次请求失败, 记 e;
 * - 解析成功但目录为空是合法来源 (空目录不当失败), 记 i。
 *
 * 拦截器返回 canned 响应, 不发真实网络请求; [AppLog] 的调用在纯 JVM 下
 * 根本进不了 buffer, 所以这组断言必须走 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RemoteCatalogFetcherLoggingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // 全新安装状态: 清空开关文件, 再按默认值重载。
        context
            .getSharedPreferences("rikkahub.preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        AppLog.clear()
        AppLog.startIfEnabled(context)
    }

    @After
    fun tearDown() {
        AppLog.clear()
        AppLog.setEnabled(context, true)
        AppLog.setMinLevel(context, 'I')
    }

    private fun entries(level: Char, prefix: String) =
        AppLog.getLogs().filter { it.level == level && it.message.startsWith(prefix) }

    private fun source(id: String, url: String) = SkillStoreSource(id = id, name = "test-$id", url = url)

    private fun clientReturning(code: Int, body: String) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Error")
                    .body(body.toResponseBody())
                    .build()
            }
            .build()

    @Test
    fun `unusable url logs one e line and fails`() = runBlocking {
        val fetcher = RemoteCatalogFetcher(context, clientReturning(200, """{"skills":[]}"""))

        val result = fetcher.fetch(source("unusable", "ftp://example.com/catalog.json"))

        assertEquals(SkillStoreSourceStatus.FAILED, result.status)
        assertEquals(1, entries('E', "source unusable has an unusable URL:").size)
    }

    @Test
    fun `fetch failure logs one e line and fails`() = runBlocking {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { throw IOException("boom-test-network") }
                .build()
        val fetcher = RemoteCatalogFetcher(context, client)

        val result = fetcher.fetch(source("netfail", "https://example.com/catalog.json"))

        assertEquals(SkillStoreSourceStatus.FAILED, result.status)
        assertEquals(1, entries('E', "fetch failed for").size)
    }

    @Test
    fun `empty catalog logs one i line and succeeds`() = runBlocking {
        val fetcher = RemoteCatalogFetcher(context, clientReturning(200, """{"skills":[]}"""))

        val result = fetcher.fetch(source("empty", "https://example.com/catalog.json"))

        assertEquals(SkillStoreSourceStatus.OK, result.status)
        assertEquals(
            1,
            entries('I', "catalog at https://example.com/catalog.json parsed but contains no skills").size,
        )
    }

    @Test
    fun `non-200 logs one e line and fails`() = runBlocking {
        val fetcher = RemoteCatalogFetcher(context, clientReturning(500, "boom"))

        val result = fetcher.fetch(source("http500", "https://example.com/catalog.json"))

        assertEquals(SkillStoreSourceStatus.FAILED, result.status)
        assertEquals(1, entries('E', "non-200 for").size)
    }
}
