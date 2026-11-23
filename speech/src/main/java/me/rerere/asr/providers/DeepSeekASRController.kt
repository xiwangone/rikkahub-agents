package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import me.rerere.common.log.AppLogger
import me.rerere.speech.http.readBoundedString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "DeepSeekASR"

// 服务端单次上限 8 MiB / 120 秒（https://api-docs.deepseek.com/zh-cn/guides/asr）；
// 客户端提前在 6 MiB 切段，留出 WAV 头与网络重传的余量。
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

// 最小段长度：16 kHz / 16 bit / mono 下 100 ms = 3200 字节。更短的碎片（stop 时的残留）
// 直接丢弃，避免发给服务端后按 400 处理。
private const val MIN_SEGMENT_BYTES = 3200

private const val MAX_ASR_RESPONSE_BYTES = 4 * 1024 * 1024

/**
 * DeepSeek 语音识别控制器（整段上传，非流式）。
 *
 * 契约来源：https://api-docs.deepseek.com/zh-cn/guides/asr
 * - `POST {baseUrl}/v1/tools/asr`，`Authorization: Bearer <apiKey>`，`multipart/form-data` 的 `file` 字段。
 * - 音频须为 16 位 PCM **WAV**（或 Ogg Opus），采样率取 8000/16000/22050/24000/32000/44100/48000；
 *   单声道或双声道（双声道只识别左声道）。
 * - 单次 ≤ 8 MiB、≤ 120 秒、上传须在 10 分钟内完成；**不支持流式**，也没有语种参数（自动识别）。
 * - 响应 `{request_id, text, duration_ms}`；错误：400 音频格式/字段错误、408 上传超时、413 超 8 MiB、
 *   429 并发超限、503 繁忙、504 识别超时。
 *
 * 采集侧按 [ASRProviderSetting.DeepSeek.segmentDurationSec] 分段（0 = 仅 stop 时上传一次），
 * 每段封成最小 WAV 后上传，文本按段顺序拼接。
 */
class DeepSeekASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DeepSeek,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null

    // 同一时刻只允许一个 flush 协程在跑, 避免乱序拼结果
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var completedTranscriptText: String = ""

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        completedTranscriptText = ""
        flushJob = null

        // 一次性 HTTP 接口，没有连接阶段，直接进入 Listening
        _state.update {
            ASRState(
                status = ASRStatus.Listening,
                isAvailable = true,
            )
        }
        startRecorder()
    }

    @Suppress("TooGenericExceptionCaught") // 有意宽 catch：尾段 flush 跨网络/JSON/录音多类异常，失败只丢尾段并记日志，stop 必须回到 Idle。
    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        // scope 已取消（dispose 先于 stop）时 launch 不会执行，状态会永远卡在 Stopping：
        // 此时尾段已无从上传，直接回 Idle。
        if (!scope.isActive) {
            _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushSegment()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "Final flush failed, tail segment transcript lost", e)
                setError(e.message ?: "DeepSeek ASR final flush failed")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle, errorMessage = null) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob =
            scope.launch(Dispatchers.IO) {
                val sampleRate = provider.sampleRate
                val minBufferSize =
                    AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                val bufferSize =
                    minBufferSize
                        .coerceAtLeast(sampleRate / 10 * 2)
                        .coerceAtLeast(4096)

                val recorder =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 2,
                    )
                audioRecord = recorder

                try {
                    recorder.startRecording()
                    val buffer = ByteArray(bufferSize)
                    val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
                    while (isActive) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val amplitude = calculateRmsAmplitude(buffer, read)
                            _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                            val shouldFlush =
                                synchronized(bufferLock) {
                                    currentBuffer.write(buffer, 0, read)
                                    if (segmentMs <= 0) {
                                        currentBuffer.size() >= MAX_SEGMENT_BYTES
                                    } else {
                                        val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                        currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
                                    }
                                }

                            if (shouldFlush) {
                                triggerFlush()
                            }
                        } else if (read < 0) {
                            throw IllegalStateException("AudioRecord read error: $read")
                        }
                    }
                } catch (e: IllegalStateException) {
                    AppLogger.e(TAG, "Audio recording failed", e)
                    setError(e.message ?: "Audio recording failed")
                } catch (e: SecurityException) {
                    AppLogger.e(TAG, "Audio recording failed", e)
                    setError(e.message ?: "Audio recording failed")
                } finally {
                    releaseRecorder()
                }
            }
    }

    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob =
            scope.launch(Dispatchers.IO) {
                try {
                    flushSegment()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // 取消要重抛：吞掉会破坏结构化并发（如 stop() 里 flushJob?.join() 等的取消语义）
                    throw ce
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Segment flush failed", e)
                    setError(e.message ?: "DeepSeek ASR segment flush failed")
                }
            }
    }

    private suspend fun flushSegment() {
        val pcmBytes =
            synchronized(bufferLock) {
                if (currentBuffer.size() < MIN_SEGMENT_BYTES) {
                    currentBuffer = ByteArrayOutputStream()
                    segmentStartElapsedMs = SystemClock.elapsedRealtime()
                    return
                }
                val bytes = currentBuffer.toByteArray()
                currentBuffer = ByteArrayOutputStream()
                segmentStartElapsedMs = SystemClock.elapsedRealtime()
                bytes
            }

        val wavBytes =
            pcm16ToWav(
                pcm = pcmBytes,
                sampleRate = provider.sampleRate,
                channels = 1,
                bitsPerSample = 16,
            )

        val multipart =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "segment.wav", wavBytes.toRequestBody(WAV_MEDIA_TYPE))
                .build()

        val request =
            Request
                .Builder()
                .url("${provider.baseUrl.trimEnd('/')}/v1/tools/asr")
                .addHeader("Authorization", "Bearer ${provider.apiKey}")
                .post(multipart)
                .build()

        val text =
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { resp ->
                    val respBody = resp.body.readBoundedString(MAX_ASR_RESPONSE_BYTES)
                    if (!resp.isSuccessful) {
                        throw IOException("DeepSeek ASR HTTP ${resp.code}: ${respBody.take(300)}")
                    }
                    val json =
                        runCatching { JSONObject(respBody) }.getOrElse {
                            throw IOException("DeepSeek ASR response is not valid JSON: ${respBody.take(300)}")
                        }
                    json.optString("text", "").trim()
                }
            }

        if (text.isNotEmpty()) {
            synchronized(completedTranscripts) {
                completedTranscripts.add(text)
                completedTranscriptText =
                    if (completedTranscriptText.isEmpty()) text else "$completedTranscriptText $text"
            }
            publishTranscript()
        }
    }

    private fun publishTranscript() {
        val transcript = completedTranscriptText
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message,
            )
        }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    companion object {
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        /**
         * 把 raw PCM16 little-endian 数据封装成最小 WAV (RIFF/WAVE/fmt/data)。
         * DeepSeek 的 `file` 字段只接受 16 位 PCM WAV 或 Ogg Opus，AudioRecord 输出的是裸 PCM。
         */
        private fun pcm16ToWav(
            pcm: ByteArray,
            sampleRate: Int,
            channels: Int,
            bitsPerSample: Int,
        ): ByteArray {
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val out = ByteArrayOutputStream(44 + dataSize)

            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 36 + dataSize)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, 16)
            writeShortLe(out, 1)
            writeShortLe(out, channels)
            writeIntLe(out, sampleRate)
            writeIntLe(out, byteRate)
            writeShortLe(out, blockAlign)
            writeShortLe(out, bitsPerSample)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLe(out, dataSize)
            out.write(pcm)
            return out.toByteArray()
        }

        private fun writeIntLe(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value and 0xFF)
            out.write(value shr 8 and 0xFF)
            out.write(value shr 16 and 0xFF)
            out.write(value shr 24 and 0xFF)
        }

        private fun writeShortLe(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value and 0xFF)
            out.write(value shr 8 and 0xFF)
        }
    }
}
