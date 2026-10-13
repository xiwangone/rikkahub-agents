package me.rerere.rikkahub.utils

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val CURRENT_VERSION = "2.1.17"

private const val ONE_APK_ASSET =
    """[{"name":"app-release.apk","browser_download_url":"https://example.com/app.apk","size":1234}]"""

private const val NO_ASSET = "[]"

/** 一个「有发布」的 GitHub releases/latest 响应；[assets] 决定有没有可安装的安装包。 */
private fun releaseBody(
    tag: String,
    assets: String = ONE_APK_ASSET,
): String =
    """
    {"tag_name":"$tag","published_at":"2026-01-01T00:00:00Z","body":"changelog","assets":$assets}
    """.trimIndent()

class UpdateCheckerTest {
    /**
     * 用 interceptor 直接短路出给定状态码/响应体，不碰真实网络（本仓库单测不联网）。
     * 404/403/5xx 的 body 用 GitHub 真实错误信封的形状。
     */
    private fun clientReturning(
        code: Int,
        body: String,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("stubbed")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

    private fun checkerWith(client: OkHttpClient): UpdateChecker =
        UpdateChecker(
            client = client,
            appScope = AppScope(),
            apiUrl = "https://api.github.com/repos/xiwangone/rikkahub-agents/releases/latest",
            currentVersionName = CURRENT_VERSION,
        )

    private suspend fun awaitState(checker: UpdateChecker): UiState<UpdateInfo> =
        withTimeout(5_000) {
            // updateState 首发是 Loading；等到 Loading 之后的第一个状态即本轮检查结果
            checker.updateState.first { it !is UiState.Loading }
        }

    /**
     * B 类判据：仍然是 `UiState.Error`（失败没被伪装成成功），但携带的是「本次未成功」类型，
     * UpdateCard 据此降级成弱提示而不是红色横幅。
     */
    private fun requireUnavailable(state: UiState<UpdateInfo>): UpdateCheckUnavailableException {
        assertTrue("expected UiState.Error, got $state", state is UiState.Error)
        val error = (state as UiState.Error).error
        assertTrue(
            "expected UpdateCheckUnavailableException, got ${error::class.simpleName}: ${error.message}",
            error is UpdateCheckUnavailableException,
        )
        return error as UpdateCheckUnavailableException
    }

    /** A 类判据：静默成功态，版本号回落到当前版本（`latest > current` 不成立 ⇒ 侧边栏不显示卡片）。 */
    private fun requireSilent(state: UiState<UpdateInfo>) {
        assertTrue("expected a non-error silent state, got $state", state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(CURRENT_VERSION, data.version)
        assertTrue(data.downloads.isEmpty())
    }

    // ---- §四.1：404 Not Found ⇒ A 类，不是 Error ----
    @Test
    fun `404 not found is treated as no release instead of a failure`() =
        runBlocking {
            val state =
                awaitState(
                    checkerWith(
                        clientReturning(
                            404,
                            """{"message":"Not Found","documentation_url":"https://docs.github.com/rest"}""",
                        ),
                    ),
                )
            requireSilent(state)
        }

    // ---- §四.2：403 限流 ⇒ B 类（临时未成功，绝不是「无发布」）----
    @Test
    fun `403 rate limit is a transient failure not a missing release`() =
        runBlocking {
            val state =
                awaitState(
                    checkerWith(
                        clientReturning(
                            403,
                            """{"message":"API rate limit exceeded for 203.0.113.4."}""",
                        ),
                    ),
                )
            val error = requireUnavailable(state)
            assertEquals(UpdateCheckReason.RATE_LIMITED, error.reason)
            assertTrue("expected status 403, got ${error.statusCode}", error.statusCode == 403)
            assertTrue(
                "真实原因要留在异常里便于定位，got ${error.detail}",
                error.detail?.contains("rate limit") == true,
            )
        }

    // ---- §四.3：5xx ⇒ B 类 ----
    @Test
    fun `server error is a transient failure`() =
        runBlocking {
            val state = awaitState(checkerWith(clientReturning(500, """{"message":"Server Error"}""")))
            val error = requireUnavailable(state)
            assertEquals(UpdateCheckReason.SERVER_ERROR, error.reason)
            assertTrue("expected status 500, got ${error.statusCode}", error.statusCode == 500)
        }

    // ---- §四.4：IOException ⇒ B 类 ----
    @Test
    fun `io exception is a transient failure`() =
        runBlocking {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor { throw IOException("simulated network down") }
                    .build()
            val state = awaitState(checkerWith(client))
            val error = requireUnavailable(state)
            assertEquals(UpdateCheckReason.NETWORK_ERROR, error.reason)
            assertTrue("network failure carries no status code, got ${error.statusCode}", error.statusCode == null)
        }

    // ---- §四.5：200 但 assets 为空数组 ⇒ 判为 A 类（有 release 但没有安装包 = 没有可更新的东西）----
    @Test
    fun `release without installable assets is treated as no available update`() =
        runBlocking {
            val state = awaitState(checkerWith(clientReturning(200, releaseBody("2.9.9", assets = NO_ASSET))))
            requireSilent(state)
        }

    // ---- §四.6 回归：200 + 更新的版本 + 有安装包 ⇒ C 类，仍然提示可更新 ----
    @Test
    fun `newer release with assets still reports an available update`() =
        runBlocking {
            val state = awaitState(checkerWith(clientReturning(200, releaseBody("2.9.9"))))
            assertTrue("expected UiState.Success, got $state", state is UiState.Success)
            val data = (state as UiState.Success).data
            assertEquals("2.9.9", data.version)
            assertEquals(1, data.downloads.size)
            assertEquals("app-release.apk", data.downloads.first().name)
            // UpdateCard 显示卡片的条件
            assertTrue(Version(data.version) > Version(CURRENT_VERSION))
        }

    // ---- §四.7 回归：apiUrl 为空 ⇒ 现有行为不变（静默成功、不发网络）----
    @Test
    fun `blank update api returns current version without network`() =
        runBlocking {
            val client =
                OkHttpClient.Builder()
                    .addInterceptor {
                        throw AssertionError("Blank update API must not perform network requests")
                    }
                    .build()
            val checker =
                UpdateChecker(
                    client = client,
                    appScope = AppScope(),
                    apiUrl = "",
                    currentVersionName = "2.1.17",
                )

            // updateState 由 appScope 承载（SharingStarted.Lazily），取到成功态即可
            val state =
                withTimeout(5_000) {
                    checker.updateState.first { it is UiState.Success }
                }
            assertTrue(state is UiState.Success)
            val success = state as UiState.Success

            assertEquals("2.1.17", success.data.version)
            assertTrue(success.data.downloads.isEmpty())
        }

    // ---- 追加：404 但 body 不是 GitHub 的 Not Found ⇒ 仍算 B 类，不掩盖成「无发布」----
    @Test
    fun `404 with an unexpected body stays a transient failure`() =
        runBlocking {
            val state = awaitState(checkerWith(clientReturning(404, """{"message":"Moved Permanently"}""")))
            val error = requireUnavailable(state)
            assertEquals(UpdateCheckReason.UNKNOWN_STATUS, error.reason)
            assertTrue("expected status 404, got ${error.statusCode}", error.statusCode == 404)
        }

    // ---- 追加：200 但响应体不是 release JSON（解析失败）⇒ B 类 ----
    @Test
    fun `unparsable success body is a transient failure`() =
        runBlocking {
            val state = awaitState(checkerWith(clientReturning(200, "<html>portal sign-in</html>")))
            val error = requireUnavailable(state)
            assertEquals(UpdateCheckReason.MALFORMED_RESPONSE, error.reason)
        }
}
