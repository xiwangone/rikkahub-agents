package me.rerere.mediagen.provider.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ProviderHttpErrorSnippetTest {
    @Test
    fun errorMessageTruncatesHugeBodyWithoutMessageField() = runBlocking {
        val hugeDetail = "y".repeat(5000)
        val client = fakeClient {
            jsonResponse(it, """{"code":"x","detail":"$hugeDetail"}""", code = 400)
        }
        val error = runCatching {
            client.executeJson(Request.Builder().url("https://example.com/").build(), "test")
        }.exceptionOrNull() as MediaGenerationApiException
        assertTrue(error.message.length < 2000)
        assertTrue(error.message.contains("truncated, "))
        assertTrue(!error.message.contains(hugeDetail))
    }

    @Test
    fun errorMessageTruncatesHugeMessageField() = runBlocking {
        val hugeMessage = "z".repeat(5000)
        val client = fakeClient {
            jsonResponse(it, """{"error":{"message":"$hugeMessage"}}""", code = 500)
        }
        val error = runCatching {
            client.executeJson(Request.Builder().url("https://example.com/").build(), "test")
        }.exceptionOrNull() as MediaGenerationApiException
        assertTrue(error.message.length < 2000)
        assertTrue(error.message.contains("truncated, "))
    }

    @Test
    fun errorMessageKeepsShortMessage() = runBlocking {
        val client = fakeClient {
            jsonResponse(it, """{"code":"InvalidParameter","message":"n must be 1"}""", code = 400)
        }
        val error = runCatching {
            client.executeJson(Request.Builder().url("https://example.com/").build(), "test")
        }.exceptionOrNull() as MediaGenerationApiException
        assertEquals("n must be 1", error.message)
    }
}

class MediaGenerationManagerWatchTest {
    private val setting = MediaGenerationProviderSetting.MiniMax(apiKey = "key")
    private val model = setting.models.single()

    @Test
    fun watchStopsAfterConsecutiveUnknownStatuses() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"task":{"id":"t1","status":"mystery"}}""")
        })
        val error = runCatching {
            manager.watch(setting, model, "t1", interval = 1.milliseconds).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("UNKNOWN"))
    }

    @Test
    fun watchTimesOutOnEndlessRunningTask() = runBlocking {
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"task":{"id":"t1","status":"running"}}""")
        })
        val error = runCatching {
            manager.watch(
                setting,
                model,
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
        val manager = MediaGenerationManager(fakeClient {
            jsonResponse(it, """{"task":{"id":"t1","status":"running"}}""")
        })
        val error = runCatching {
            manager.watch(
                setting,
                model,
                "t1",
                interval = 1.milliseconds,
                timeout = 1.hours,
                maxAttempts = 3,
            ).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("after 3 attempts"))
    }

    @Test
    fun generateForwardsWatchBounds() = runBlocking {
        var queries = 0
        val manager = MediaGenerationManager(fakeClient { request ->
            if (request.method == "POST") {
                jsonResponse(request, """{"task_id":"t1"}""")
            } else {
                queries++
                jsonResponse(request, """{"task":{"id":"t1","status":"running"}}""")
            }
        })
        val error = runCatching {
            manager.generate(
                setting,
                model,
                MediaGenerationRequest(prompt = "跑车"),
                interval = 1.milliseconds,
                timeout = 50.milliseconds,
                maxAttempts = 10_000,
            ).toList()
        }.exceptionOrNull() as IllegalStateException
        assertTrue(error.message!!.contains("Timed out"))
        assertTrue(queries > 0)
    }
}
