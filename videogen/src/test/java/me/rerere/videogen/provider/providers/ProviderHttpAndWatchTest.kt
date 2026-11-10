package me.rerere.videogen.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.videogen.provider.VideoGenerationManager
import me.rerere.videogen.provider.VideoGenerationProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

private fun fakeClient(respond: (Request) -> Response): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor { chain -> respond(chain.request()) }
        .build()

private fun jsonResponse(request: Request, body: String, code: Int = 200): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

class ProviderHttpErrorSnippetTest {
    @Test
    fun errorMessageTruncatesHugeBodyWithoutMessageField() = runBlocking {
        val hugeDetail = "y".repeat(5000)
        val client = fakeClient {
            jsonResponse(it, """{"code":"x","detail":"$hugeDetail"}""", code = 400)
        }
        val error = runCatching {
            client.executeJson(Request.Builder().url("https://example.com/").build(), "test")
        }.exceptionOrNull() as VideoGenerationApiException
        assertTrue(error.message.length < 2000)
        assertTrue(error.message.contains("truncated, "))
        assertTrue(!error.message.contains(hugeDetail))
    }

    @Test
    fun errorMessageKeepsShortMessage() = runBlocking {
        val client = fakeClient {
            jsonResponse(it, """{"code":"InvalidParameter","message":"n must be 1"}""", code = 400)
        }
        val error = runCatching {
            client.executeJson(Request.Builder().url("https://example.com/").build(), "test")
        }.exceptionOrNull() as VideoGenerationApiException
        assertEquals("n must be 1", error.message)
    }
}

class VideoGenerationManagerWatchTest {
    private val setting = VideoGenerationProviderSetting.Aliyun(apiKey = "key", model = "wan2.5-video")

    @Test
    fun watchStopsAfterConsecutiveUnknownStatuses() = runBlocking {
        val manager = VideoGenerationManager(fakeClient {
            jsonResponse(it, """{"output":{"task_id":"t1","task_status":"SUSPENDED"}}""")
        })
        val error = runCatching {
            manager.watch(setting, "t1", interval = 1.milliseconds).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("UNKNOWN"))
    }

    @Test
    fun watchTimesOutOnEndlessRunningTask() = runBlocking {
        val manager = VideoGenerationManager(fakeClient {
            jsonResponse(it, """{"output":{"task_id":"t1","task_status":"RUNNING"}}""")
        })
        val error = runCatching {
            manager.watch(
                setting,
                "t1",
                interval = 1.milliseconds,
                timeout = 50.milliseconds,
                maxAttempts = 10_000,
            ).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("Timed out"))
    }

    @Test
    fun watchStopsAfterMaxAttempts() = runBlocking {
        val manager = VideoGenerationManager(fakeClient {
            jsonResponse(it, """{"output":{"task_id":"t1","task_status":"RUNNING"}}""")
        })
        val error = runCatching {
            manager.watch(
                setting,
                "t1",
                interval = 1.milliseconds,
                timeout = 1.hours,
                maxAttempts = 3,
            ).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("after 3 attempts"))
    }
}
