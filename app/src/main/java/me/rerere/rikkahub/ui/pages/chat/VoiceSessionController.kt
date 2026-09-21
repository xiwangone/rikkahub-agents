// 本文件刻意捕获宽类型异常再区分「协程取消」与其他失败：取消是正常控制流（换页面/退出语音），
// 必须先解包 CancellationException，否则会把正常退出当成错误显示。函数规模与圈复杂度是
// 「采集 / 等待回复 / 朗读」三件事在同一事件循环里协作的结果，拆分反而会隐藏状态耦合。
@file:Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "CyclomaticComplexMethod")

package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException

/** 语音模式的阶段（供 UI 展示）。 */
enum class VoicePhase { Off, Connecting, Listening, Transcribing, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    /** 已提交但还没拿到回复的消息条数（边说边排队时 > 0）。 */
    val pendingReplies: Int = 0,
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/**
 * 语音模式会话控制器：**采集与生成互相独立**，只有朗读需要短暂独占麦克风。
 *
 * 职责边界：控制器不持有聊天队列，只做两件事 ——
 * ① 把识别出的整句交给 `enqueueMessage`（→ 进聊天队列，返回该轮的回复句柄）；
 * ② 按**提交顺序**等待回复，拿到文本后朗读。
 *
 * 因此「用户说话」与「AI 回答」可以重叠：说话时前一轮的答案还在生成，答案生成完先朗读、
 * 朗读期间暂停采集（避免喇叭回声被当成新一句话），朗读结束再继续听。
 */
class VoiceSessionController(
    private val sessionScope: CoroutineScope,
    private val stringProvider: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    private sealed interface Event {
        data class Utterance(val text: String) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception) : Event
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
    ) {
        if (job?.isCompleted == false) return
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = sessionScope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        error = when (e) {
                            is TimeoutCancellationException ->
                                "Speech recognition timed out. Restart voice mode."

                            is MessageQueuePausedException -> stringProvider(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: stringProvider(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                stopSpeaking()
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null

        // 按提交顺序等待回复，且**不阻塞采集**（撤销队列条目会得到 null）
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                // 采集中有整句待朗读时，先把麦克风让给喇叭（否则回声会被识别成新句子）
                if (speak != null && replies.isNotEmpty() && asr?.state?.value?.voiceTurn?.itemId == null) {
                    capture?.cancelAndJoin()
                    capture = null
                    asr = null
                    mutableState.update { it.copy(phase = VoicePhase.Speaking) }
                    speak(replies.removeFirst())
                    delay(300) // 等扬声器余音衰减再开麦
                    continue
                }
                if (capture == null) {
                    mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "") }
                    val recorder = createAsr()
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            events.send(Event.Utterance(listen(recorder)))
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e))
                        }
                    }
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        capture?.join()
                        capture = null
                        asr = null
                        if (event.text.isNotBlank()) {
                            val reply = enqueueMessage(event.text)
                            mutableState.update {
                                it.copy(transcript = event.text, pendingReplies = it.pendingReplies + 1)
                            }
                            submitted.send(reply)
                        }
                    }

                    is Event.Reply -> {
                        mutableState.update {
                            it.copy(pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0))
                        }
                        event.text?.takeIf { speak != null && it.isNotBlank() }?.let { replies.addLast(it) }
                    }

                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            capture?.cancel()
            // 已提交的消息归属聊天队列：退出语音模式只是断开旁观，不撤销它们
            submitted.close()
        }
    }

    /** 一轮听写：等待识别开始 → 等待说话结束 → 等待最终文本。 */
    private suspend fun listen(asr: ASRController): String {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also {
                    check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) {
                        "Unable to start speech recognition"
                    }
                }
            }
            val ended = withTimeout(120_000) {
                asr.state.onEach {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.status == ASRStatus.Listening || it.voiceTurn.isComplete) {
                        "Speech recognition disconnected"
                    }
                    mutableState.update { current ->
                        current.copy(phase = VoicePhase.Listening, transcript = it.transcript)
                    }
                }.first { it.voiceTurn.speechEnded }
            }
            asr.pauseCapture()
            mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended.transcript) }
            return withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        job?.cancel()
        mutableState.value = VoiceSessionState()
    }
}
