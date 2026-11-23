package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.common.log.AppLogger
import me.rerere.speech.http.MAX_TTS_AUDIO_BYTES
import me.rerere.speech.http.readBoundedBytes
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

private const val TAG = "DeepSeekTTSProvider"

/**
 * DeepSeek 语音合成（整段合成接口，**不是** OpenAI 兼容格式）。
 *
 * 契约来源：https://api-docs.deepseek.com/zh-cn/guides/tts
 * - `POST {baseUrl}/v1/tools/tts`，`Authorization: Bearer <apiKey>`，JSON 体 `{text, voice_id, format}`。
 * - 音频边合成边返回：`format=wav` 返回 audio/wav；`format=pcm` 返回 24 kHz / 16 位 / 单声道裸 PCM。
 * - 不支持调节语速；语种按文本自动识别。
 * - 错误码：400 无可朗读文本或内容审核（Content Exists Risk）、429 并发超限、503 繁忙、504 超时；
 *   音频已开始返回后中途断开视为失败。
 *
 * 这里请求 `pcm`（24 kHz / 16 bit / mono），由播放侧补 WAV 头；请求体上限 64 KiB、
 * 单次最多 10,000 计费字符，超过由服务端拒绝后按普通 HTTP 错误上报。
 */
class DeepSeekTTSProvider : TTSProvider<TTSProviderSetting.DeepSeek> {
    private val httpClient =
        OkHttpClient
            .Builder()
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.DeepSeek,
        request: TTSRequest,
    ): Flow<AudioChunk> =
        flow {
            val requestBody =
                JSONObject().apply {
                    put("text", request.text)
                    if (providerSetting.voiceId.isNotBlank()) {
                        put("voice_id", providerSetting.voiceId)
                    }
                    put("format", "pcm")
                }

            AppLogger.i(TAG, "generateSpeech: voice=${providerSetting.voiceId}")

            val httpRequest =
                Request
                    .Builder()
                    .url("${providerSetting.baseUrl.trimEnd('/')}/v1/tools/tts")
                    .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

            // use{} 确保错误分支与读流后都关闭连接：泄漏的连接会占满 OkHttp 连接池，
            // 连续失败几次后后续请求全部排队超时。
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    AppLogger.e(TAG, "generateSpeech: ${response.code} ${response.message}")
                    AppLogger.e(TAG, "generateSpeech: $errorBody")
                    throw TTSProviderException(
                        message = "DeepSeek TTS request failed: ${response.code} ${response.message}",
                        statusCode = response.code,
                    )
                }

                val audioData = response.body.readBoundedBytes(MAX_TTS_AUDIO_BYTES)

                emit(
                    AudioChunk(
                        data = audioData,
                        format = AudioFormat.PCM,
                        sampleRate = 24000,
                        isLast = true,
                        metadata =
                            mapOf(
                                "provider" to "deepseek",
                                "voiceId" to providerSetting.voiceId,
                            ),
                    ),
                )
            }
        }
}
