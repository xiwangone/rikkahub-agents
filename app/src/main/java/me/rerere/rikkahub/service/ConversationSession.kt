package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 会话是否已完成首次数据库/预设初始化；一旦初始化或已有内存消息，切换会话时不再用数据库旧快照覆盖内存流式状态
    internal val initMutex = Mutex()

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun markInitialized() {
        isInitialized = true
    }

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // The floating surface follows the assistant message emitted by this exact turn,
    // not the conversation tail (which may belong to another branch or regeneration).
    val floatingReplyMessageId = MutableStateFlow<Uuid?>(null)
    val floatingGenerationStartedAt = MutableStateFlow<Long?>(null)

    fun beginFloatingTurn() {
        floatingReplyMessageId.value = null
        floatingGenerationStartedAt.value = System.currentTimeMillis()
    }

    fun recordFloatingReply(messageId: Uuid) {
        floatingReplyMessageId.value = messageId
    }

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    /** 当前活跃的生成任务（含接续链）。停止生成时全部取消。 */
    private val activeJobs = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating || _generationJob.value != null

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int =
        refCount.incrementAndGet().also {
            cancelIdleCheck()
            AppLog.d(TAG, "acquire $id (refs=$it)")
        }

    fun release(): Int =
        refCount.decrementAndGet().also {
            AppLog.d(TAG, "release $id (refs=$it)")
            if (it <= 0) scheduleIdleCheck()
        }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * 设置当前生成任务。
     *
     * @param cancelPrevious true（默认）= 取代语义，取消前一个任务；
     *   false = 接续语义，不取消前一个任务（用于连续工具审批，避免打断上一个任务的审批写入）。
     */
    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.getAndUpdate { job }
        if (cancelPrevious && previous != null) {
            if (previous.isActive) {
                val caller =
                    Thread.currentThread().stackTrace
                        .drop(1)
                        .take(4)
                        .joinToString(" <- ") {
                            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                        }
                AppLog.w(TAG, "setJob cancelPrevious=true cancels ACTIVE job; caller=$caller")
            }
            previous.cancel()
        }
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            activeJobs.remove(job)
            if (!cancelPrevious && cause is CancellationException) previous?.cancel()
            if (_generationJob.compareAndSet(job, null)) {
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    /** 取消所有活跃生成任务（含接续链），返回被取消的任务快照。 */
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        jobs.asReversed().forEach { it.cancel() }
    }

    fun getJob(): Job? = _generationJob.value

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob =
            scope.launch {
                delay(IDLE_TIMEOUT_MS)
                if (refCount.get() <= 0 && !isGenerating) {
                    onIdle(id)
                }
            }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        val job = _generationJob.getAndUpdate { null }
        job?.cancel()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
