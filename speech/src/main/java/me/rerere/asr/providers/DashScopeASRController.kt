@file:Suppress("TooGenericExceptionCaught") // ASR 网络与协议异常类型不可枚举；取消已单独透传，其余统一上报

package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import me.rerere.common.log.AppLogger

private const val TAG = "DashScopeASR"
private const val MAX_WEBSOCKET_QUEUE_BYTES = 100_000L
private const val SESSION_FINISH_TIMEOUT_MS = 10_000L

class DashScopeASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.DashScope,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var webSocket: WebSocket? = null
    private var recorderJob: Job? = null
    private var finishTimeoutJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())
    private val partialTranscripts = ConcurrentHashMap<String, String>()

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
        completedTranscripts.clear()
        partialTranscripts.clear()
        _state.update {
            ASRState(
                status = ASRStatus.Connecting,
                isAvailable = true,
            )
        }

        val request =
            Request
                .Builder()
                .url(provider.websocketEndpoint())
                .addHeader("Authorization", "Bearer ${provider.apiKey}")
                .build()

        webSocket =
            httpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        if (this@DashScopeASRController.webSocket !== webSocket || state.value.status != ASRStatus.Connecting) {
                            webSocket.close(1000, "cancelled")
                            return
                        }
                        webSocket.send(provider.sessionUpdateEvent().toString())
                        _state.update { it.copy(status = ASRStatus.Listening, errorMessage = null) }
                        startRecorder(webSocket)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (this@DashScopeASRController.webSocket === webSocket) {
                            handleServerEvent(webSocket, text)
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        AppLogger.e(TAG, "DashScope ASR websocket failed (${response?.code ?: "no status"})", t)
                        if (this@DashScopeASRController.webSocket !== webSocket) return
                        finishTimeoutJob?.cancel()
                        finishTimeoutJob = null
                        this@DashScopeASRController.webSocket = null
                        releaseRecorder()
                        setError(t.message ?: "ASR websocket failed")
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        if (this@DashScopeASRController.webSocket !== webSocket) return
                        finishTimeoutJob?.cancel()
                        finishTimeoutJob = null
                        this@DashScopeASRController.webSocket = null
                        releaseRecorder()
                        _state.update {
                            it.copy(
                                status = ASRStatus.Idle,
                                errorMessage = null,
                            )
                        }
                    }
                },
            )
    }

    override fun pauseCapture() {
        val activeRecorderJob = recorderJob
        activeRecorderJob?.cancel()
        // AudioRecord.read() blocks; stop it so cancellation can reach the recorder's finally block.
        runCatching { audioRecord?.stop() }
        scope.launch(Dispatchers.IO) {
            activeRecorderJob?.join()
            releaseRecorder()
        }
    }

    override fun stop() {
        val wasListening = state.value.status == ASRStatus.Listening
        val activeRecorderJob = recorderJob
        val socket = webSocket
        if (socket == null && activeRecorderJob == null) {
            releaseRecorder()
            _state.update { it.copy(status = ASRStatus.Idle) }
            return
        }

        _state.update { it.copy(status = ASRStatus.Stopping) }
        activeRecorderJob?.cancel()
        // AudioRecord.read() is blocking: stop it before joining, then send session.finish only
        // after no producer can enqueue another audio frame.
        runCatching { audioRecord?.stop() }
        finishTimeoutJob?.cancel()
        finishTimeoutJob = scope.launch {
            activeRecorderJob?.join()
            releaseRecorder()
            if (socket == null) {
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            if (webSocket !== socket) return@launch
            if (!wasListening) {
                socket.cancel()
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            if (!socket.send(sessionFinishEvent().toString())) {
                AppLogger.w(TAG, "Failed to finish DashScope ASR session; closing WebSocket")
                socket.cancel()
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
                return@launch
            }
            delay(SESSION_FINISH_TIMEOUT_MS)
            if (webSocket === socket) {
                AppLogger.w(TAG, "Timed out waiting for session.finished; closing WebSocket")
                socket.close(1000, "session finish timeout")
                webSocket = null
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        runCatching { audioRecord?.stop() }
        releaseRecorder()
        finishTimeoutJob?.cancel()
        finishTimeoutJob = null
        webSocket?.cancel()
        webSocket = null
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder(socket: WebSocket) {
        recorderJob?.cancel()
        recorderJob =
            scope.launch(Dispatchers.IO) {
                val minBufferSize =
                    AudioRecord.getMinBufferSize(
                        provider.sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                val bufferSize =
                    minBufferSize
                        .coerceAtLeast(provider.sampleRate / 10 * 2)
                        .coerceAtLeast(4096)

                val recorder =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        provider.sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 2,
                    )
                audioRecord = recorder

                try {
                    recorder.startRecording()
                    val buffer = ByteArray(bufferSize)
                    while (isActive) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            val amplitude = calculateRmsAmplitude(buffer, read)
                            _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }
                            if (socket.queueSize() < MAX_WEBSOCKET_QUEUE_BYTES) {
                                val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                                val event =
                                    JSONObject()
                                        .put("event_id", "evt_${System.currentTimeMillis()}")
                                        .put("type", "input_audio_buffer.append")
                                        .put("audio", encoded)
                                socket.send(event.toString())
                            } else {
                                AppLogger.w(TAG, "WebSocket queue full, dropping audio frame")
                            }
                        } else if (read < 0) {
                            throw IllegalStateException("AudioRecord read error: $read")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Audio recording failed", e)
                    setError(e.message ?: "Audio recording failed")
                } finally {
                    releaseRecorder(recorder)
                }
            }
    }

    private fun handleServerEvent(socket: WebSocket, text: String) {
        if (webSocket !== socket) return
        val event =
            runCatching { JSONObject(text) }.getOrElse {
                AppLogger.w(TAG, "Invalid realtime event", it)
                return
            }

        when (val type = event.optString("type")) {
            "input_audio_buffer.speech_started" -> {
                val id = event.optString("item_id")
                _state.update { it.copy(voiceTurn = it.voiceTurn.started(id)) }
            }

            "input_audio_buffer.speech_stopped" -> {
                val id = event.optString("item_id")
                _state.update { it.copy(voiceTurn = it.voiceTurn.stopped(id)) }
            }

            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = event.optString("item_id", "default")
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    partialTranscripts[itemId] = (partialTranscripts[itemId] ?: "") + delta
                    publishTranscript()
                }
            }

            "conversation.item.input_audio_transcription.text" -> {
                val itemId = event.optString("item_id", "default")
                val preview = event.optString("text") + event.optString("stash")
                if (preview.isNotEmpty()) {
                    partialTranscripts[itemId] = preview
                    publishTranscript()
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val itemId = event.optString("item_id", "default")
                val transcript = event.optString("transcript").trim()
                _state.update { it.copy(voiceTurn = it.voiceTurn.completed(itemId, transcript)) }
                partialTranscripts.remove(itemId)
                if (transcript.isNotEmpty()) {
                    completedTranscripts.add(transcript)
                }
                publishTranscript()
            }

            "conversation.item.input_audio_transcription.failed" -> {
                val itemId = event.optString("item_id", "default")
                val error = event.optJSONObject("error")
                partialTranscripts.remove(itemId)
                publishTranscript()
                _state.update { it.copy(errorMessage = error?.optString("message") ?: "ASR transcription failed") }
            }

            "error" -> {
                val error = event.optJSONObject("error")
                setError(error?.optString("message") ?: "ASR realtime error")
            }

            "session.finished" -> {
                finishTimeoutJob?.cancel()
                finishTimeoutJob = null
                socket.close(1000, "session finished")
            }

            else -> {
                Log.v(TAG, "Ignored realtime event: $type")
            }
        }
    }

    private fun publishTranscript() {
        val transcript =
            (completedTranscripts + partialTranscripts.values)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch {
            onTranscriptChange?.invoke(transcript)
        }
    }

    private fun setError(message: String) {
        _state.update {
            it.copy(
                status = ASRStatus.Error,
                errorMessage = message,
            )
        }
    }

    private fun releaseRecorder(recording: AudioRecord? = audioRecord) {
        if (recording == null) return
        runCatching { recording.stop() }
        runCatching { recording.release() }
        if (audioRecord === recording) {
            audioRecord = null
            recorderJob = null
        }
    }
}

private fun sessionFinishEvent(): JSONObject =
    JSONObject()
        .put("event_id", "evt_session_finish_${System.currentTimeMillis()}")
        .put("type", "session.finish")

private fun ASRProviderSetting.DashScope.websocketEndpoint(): String {
    val endpoint = websocketUrl.trim().trimEnd('/').replace("/api-ws/v1/inference", "/api-ws/v1/realtime")
    val separator = if (endpoint.contains("?")) "&" else "?"
    return if (endpoint.contains("model=")) {
        endpoint
    } else {
        "${endpoint}${separator}model=$model"
    }
}

private fun ASRProviderSetting.DashScope.sessionUpdateEvent(): JSONObject {
    val transcription = JSONObject()
    if (language.isNotBlank()) transcription.put("language", language)

    val session =
        JSONObject()
            .put("input_audio_format", "pcm")
            .put("sample_rate", sampleRate)
            .put("input_audio_transcription", transcription)

    session.put(
        "turn_detection",
        JSONObject()
            .put("type", "server_vad")
            .put("threshold", vadThreshold.coerceIn(-1.0f, 1.0f))
            .put("silence_duration_ms", silenceDurationMs.coerceIn(200, 6000)),
    )

    return JSONObject()
        .put("event_id", "evt_session_update")
        .put("type", "session.update")
        .put("session", session)
}
