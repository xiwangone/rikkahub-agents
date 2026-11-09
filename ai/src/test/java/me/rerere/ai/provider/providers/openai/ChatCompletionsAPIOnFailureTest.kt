package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0: SSE [EventSourceListener.onFailure] with a null throwable and an empty
 * error body must surface as a stream failure, not as a normal completion.
 *
 * OkHttp's SSE implementation calls `onFailure(eventSource, t = null, response)`
 * when the HTTP response is not successful (see `RealEventSource.processResponse`:
 * `if (!response.isSuccessful) listener.onFailure(this, null, response)`).
 * [ChatCompletionsAPI.streamText] then runs `close(exception)` with
 * `exception == null`, which closes the `callbackFlow` normally — the caller
 * sees a clean end of stream instead of the failure.
 *
 * This test drives the real [ChatCompletionsAPI.streamText] with an OkHttp
 * interceptor that answers every request with HTTP 500 and an empty body, so
 * the production `onFailure` path is exercised end to end.
 */
class ChatCompletionsAPIOnFailureTest {

    @Test
    fun `stream failure with null throwable and empty body must fail the flow, not complete normally`() =
        runBlocking {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body("".toResponseBody(null))
                        .build()
                }
                .build()

            val api = ChatCompletionsAPI(client, KeyRoulette.default())
            val result = runCatching {
                api.streamText(
                    providerSetting = ProviderSetting.OpenAI(
                        apiKey = "fixture-key-not-a-credential",
                    ),
                    messages = listOf(UIMessage.user("hi")),
                    params = TextGenerationParams(model = Model(modelId = "gpt-test")),
                ).toList()
            }
            // NB: never assert inside a try/catch(Throwable) here — fail() throws
            // AssertionError, which catch(Throwable) would swallow and fake a pass.
            assertTrue(
                "streamText must fail when the SSE connection fails with no throwable " +
                    "and an empty error body, but it completed normally with chunks=${result.getOrNull()}",
                result.isFailure,
            )
        }
}
