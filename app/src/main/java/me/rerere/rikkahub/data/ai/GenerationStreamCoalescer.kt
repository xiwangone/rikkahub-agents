package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.StreamChunk
import me.rerere.rikkahub.data.log.AppLog

// 类名 25 字符, 超过 23 字符会被 logcat 截断, Generation 缩写为 Gen。
private const val TAG = "GenStreamCoalescer"

internal fun newReasoningRepetitionDetector(): OutputRepetitionDetector = OutputRepetitionDetector(
    // 思考流比正文更易撞上短片段重复（路径 / 标识符 / 数字）：下限仍到 8 字符，
    // 但短周期按 repeatsFor 要 5 次（40 字符）才判，检查窗口也放宽到 120 字符。
    minPeriod = 8,
    minRepeats = 4,
    minCheckIntervalChars = 120,
)

/**
 * 按窗口合并相邻的同轨增量：每个 delta 都做一次折叠 + 全量投递，长回复下是 O(n²)。
 * 合并保持顺序与内容完全一致（同一 id 的连续增量拼接成一条），窗口为 0 时逐块透出。
 *
 * 首块立即透出以消除首字迟滞；窗口内累积的分块由尾随定时器在窗口到期时主动刷出，
 * 避免模型短暂停顿时缓冲区分块滞留导致“先出几个字停住、随后蹦出一大段话”。
 */
internal fun Flow<StreamChunk>.coalesceStreamDeltas(
    windowMs: Long,
    clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
): Flow<StreamChunk> = channelFlow {
    if (windowMs <= 0L) {
        collect { send(it) }
        return@channelFlow
    }
    val mutex = Mutex()
    val buffer = mutableListOf<StreamChunk>()
    var lastFlushMs = 0L
    var hasFlushedOnce = false
    var trailingJob: Job? = null

    suspend fun flushLocked() {
        trailingJob?.cancel()
        trailingJob = null
        if (buffer.isEmpty()) return
        // 先取快照再清空：mergeAdjacentDeltas 在只有一条时会原样返回入参，
        // 直接 clear() 会把待发的那条一起清掉（段首/尾部丢字的根因）。
        val batch = buffer.toList()
        buffer.clear()
        hasFlushedOnce = true
        lastFlushMs = clock()
        mergeAdjacentDeltas(batch).forEach { send(it) }
    }

    try {
        collect { chunk ->
            val coalescable = chunk is StreamChunk.TextDelta || chunk is StreamChunk.ReasoningDelta
            mutex.withLock {
                if (!coalescable) {
                    flushLocked()
                    send(chunk)
                    return@withLock
                }
                buffer.add(chunk)
                val now = clock()
                val elapsed = now - lastFlushMs
                if (!hasFlushedOnce || elapsed >= windowMs) {
                    flushLocked()
                } else if (trailingJob?.isActive != true) {
                    val waitMs = (windowMs - elapsed).coerceAtLeast(1L)
                    trailingJob = launch {
                        delay(waitMs)
                        mutex.withLock { flushLocked() }
                    }
                }
            }
        }
    } finally {
        trailingJob?.cancel()
        // 终止（正常/异常/取消）都补齐窗口内未透出的分块，尾部内容不丢。
        // 取消必须向上传播（否则上层以为流正常结束）；其他投递失败记日志，不再静默吞掉。
        try {
            mutex.withLock { flushLocked() }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.w(TAG, "Final flush failed; trailing deltas may be lost", failure)
        }
    }
}

internal fun mergeAdjacentDeltas(chunks: List<StreamChunk>): List<StreamChunk> {
    // 早返回也要给新列表：调用方可能持有入参并随后清空它（列表别名会让内容凭空消失）。
    if (chunks.size < 2) return chunks.toList()
    val out = mutableListOf<StreamChunk>()
    for (chunk in chunks) {
        val prev = out.lastOrNull()
        val merged = when {
            prev is StreamChunk.TextDelta && chunk is StreamChunk.TextDelta && prev.id == chunk.id ->
                prev.copy(text = prev.text + chunk.text)

            prev is StreamChunk.ReasoningDelta && chunk is StreamChunk.ReasoningDelta &&
                prev.id == chunk.id && prev.reasoningType == chunk.reasoningType ->
                prev.copy(text = prev.text + chunk.text)

            else -> null
        }
        if (merged != null) out[out.lastIndex] = merged else out.add(chunk)
    }
    return out
}
