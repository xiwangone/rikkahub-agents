package me.rerere.rikkahub.reliability

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the version comparator, plus stubbed-transport tests for the
 * "404 = nothing published" vs "check failed" split (issue #13). We never hit real
 * network: an application interceptor short-circuits the call with a canned response.
 */
class GitHubReleaseCheckerTest {

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

    private val checker = GitHubReleaseChecker(OkHttpClient())

    @Test fun `agent revision bump is newer`() {
        assertTrue(checker.isNewer("2.1.15-agent.1", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("v2.1.15-agent.1", "2.1.15-agent.0"))
    }

    @Test fun `patch bump is newer`() {
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.0"))
        assertTrue(checker.isNewer("2.1.16-agent.0", "2.1.15-agent.99"))
    }

    @Test fun `minor bump is newer regardless of patch and agent`() {
        assertTrue(checker.isNewer("2.2.0-agent.0", "2.1.99-agent.99"))
    }

    @Test fun `major bump is newer regardless of all else`() {
        assertTrue(checker.isNewer("3.0.0-agent.0", "2.99.99-agent.99"))
    }

    @Test fun `same version is not newer`() {
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("v2.1.15-agent.0", "2.1.15-agent.0"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(checker.isNewer("2.1.14-agent.99", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "2.1.15-agent.1"))
    }

    @Test fun `missing agent suffix treated as agent_0`() {
        assertTrue(checker.isNewer("2.1.16", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15", "2.1.15-agent.0"))
    }

    @Test fun `unparseable strings fail safe to false`() {
        assertFalse(checker.isNewer("totally bogus", "2.1.15-agent.0"))
        assertFalse(checker.isNewer("2.1.15-agent.0", "totally bogus"))
    }

    @Test fun `partial version comparator works`() {
        assertTrue(checker.isNewer("3", "2.99.99-agent.99"))
        assertFalse(checker.isNewer("2", "2.0.0-agent.0"))
    }

    @Test fun `404 not found is no release rather than a failed check`() {
        val stubbed = GitHubReleaseChecker(clientReturning(404, """{"message":"Not Found"}"""))
        val result = runBlocking { stubbed.check() }
        assertTrue(
            "expected NoRelease, got $result",
            result is GitHubReleaseChecker.CheckResult.NoRelease,
        )
    }

    @Test fun `403 rate limit stays a failed check and keeps the status code`() {
        val stubbed =
            GitHubReleaseChecker(clientReturning(403, """{"message":"API rate limit exceeded for 203.0.113.4."}"""))
        val result = runBlocking { stubbed.check() }
        val failed = result as? GitHubReleaseChecker.CheckResult.Failed
        assertTrue("expected Failed, got $result", failed != null)
        assertTrue("status code must stay visible, got ${failed?.message}", failed?.message?.contains("403") == true)
    }

    @Test fun `404 with an unexpected body stays a failed check`() {
        val stubbed = GitHubReleaseChecker(clientReturning(404, "<html>portal sign-in</html>"))
        val result = runBlocking { stubbed.check() }
        assertTrue(
            "expected Failed (cannot prove absence of a release here), got $result",
            result is GitHubReleaseChecker.CheckResult.Failed,
        )
    }
}
