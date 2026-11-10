package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import me.rerere.common.log.AppLogger
import me.rerere.speech.http.MAX_ERROR_BODY_BYTES
import me.rerere.speech.http.MAX_TTS_AUDIO_BYTES
import me.rerere.speech.http.readBoundedBytes
import me.rerere.speech.http.readBoundedString
import me.rerere.speech.http.truncateForError

private const val TAG = "OpenAITTSProvider"

class OpenAITTSProvider : TTSProvider<TTSProviderSetting.OpenAI> {
    private val httpClient =
        OkHttpClient
            .Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.OpenAI,
        request: TTSRequest,
    ): Flow<AudioChunk> =
        flow {
            val requestBody =
                JSONObject().apply {
                    put("model", providerSetting.model)
                    put("input", request.text)
                    put("voice", providerSetting.voice)
                    put("response_format", "mp3") // Default to MP3
                }

            AppLogger.i(TAG, "generateSpeech: $requestBody")

            val httpRequest =
                Request
                    .Builder()
                    .url("${providerSetting.baseUrl}/audio/speech")
                    .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

            // Read the error body (bounded) before throwing: reading closes the body and
            // releases the connection, and the snippet keeps the failure diagnosable.
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.body.readBoundedString(MAX_ERROR_BODY_BYTES) }.getOrNull()
                    AppLogger.e(TAG, "TTS request failed: ${response.code} ${response.message}: ${errorBody?.truncateForError()}")
                    throw TTSProviderException(
                        message = "TTS request failed: ${response.code} ${response.message}",
                        statusCode = response.code,
                    )
                }

                val audioData = response.body.readBoundedBytes(MAX_TTS_AUDIO_BYTES)

                emit(
                    AudioChunk(
                        data = audioData,
                        format = AudioFormat.MP3,
                        isLast = true,
                        metadata =
                            mapOf(
                                "provider" to "openai",
                                "model" to providerSetting.model,
                                "voice" to providerSetting.voice,
                            ),
                    ),
                )
            }
        }
}
