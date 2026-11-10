package me.rerere.videogen.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.videogen.model.VideoGenerationRequest
import me.rerere.videogen.model.VideoGenerationStatus
import me.rerere.videogen.model.VideoGenerationTask
import me.rerere.videogen.provider.providers.AliyunVideoGenerationProvider
import me.rerere.videogen.provider.providers.MiniMaxVideoGenerationProvider
import me.rerere.videogen.provider.providers.VolcengineVideoGenerationProvider
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class VideoGenerationManager(
    client: OkHttpClient = OkHttpClient(),
) {
    private val aliyun = AliyunVideoGenerationProvider(client)
    private val volcengine = VolcengineVideoGenerationProvider(client)
    private val miniMax = MiniMaxVideoGenerationProvider(client)

    suspend fun create(
        setting: VideoGenerationProviderSetting,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask> = provider(setting).createUnsafe(setting, request)

    suspend fun query(
        setting: VideoGenerationProviderSetting,
        taskId: String,
    ): Result<VideoGenerationTask> = provider(setting).queryUnsafe(setting, taskId)

    /**
     * 轮询并依次发出服务端状态。Flow 被取消时，轮询也会立即停止。
     *
     * [timeout] 与 [maxAttempts] 共同封顶（先到为准）；连续 [MAX_UNKNOWN_FAILURES] 次
     * 收到 UNKNOWN 状态（不在终态集合内、服务端可能返回了未映射的新状态）视为任务卡住，
     * 直接失败而不是等到超时。
     */
    fun watch(
        setting: VideoGenerationProviderSetting,
        taskId: String,
        interval: Duration = 15.seconds,
        timeout: Duration = DEFAULT_WATCH_TIMEOUT,
        maxAttempts: Int = maxWatchAttempts(timeout, interval),
    ): Flow<VideoGenerationTask> = flow {
        require(interval.isPositive()) { "interval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        val startedAt = TimeSource.Monotonic.markNow()
        var attempts = 0
        var unknownFailures = 0
        while (true) {
            check(startedAt.elapsedNow() < timeout) {
                "Timed out waiting for video task $taskId after $timeout"
            }
            check(attempts < maxAttempts) {
                "Stopped polling video task $taskId after $maxAttempts attempts"
            }
            val task = query(setting, taskId).getOrThrow()
            attempts++
            emit(task)
            if (task.isTerminal) return@flow
            if (task.status == VideoGenerationStatus.UNKNOWN) {
                unknownFailures++
                if (unknownFailures >= MAX_UNKNOWN_FAILURES) {
                    throw IllegalStateException(
                        "Video task $taskId reported UNKNOWN status $unknownFailures times in a row; stopping poll",
                    )
                }
            } else {
                unknownFailures = 0
            }
            delay(interval)
        }
    }

    companion object {
        // Mirrors MediaCreationService.awaitTask: a task that never reaches a terminal
        // state is abandoned after 24h, and 20 consecutive unknown statuses are
        // treated as a stuck task rather than polled forever.
        private val DEFAULT_WATCH_TIMEOUT = 24.hours
        private const val MAX_UNKNOWN_FAILURES = 20

        // Number of polls that fit into the timeout at the given interval, so the
        // attempt cap and the deadline agree by default.
        private fun maxWatchAttempts(timeout: Duration, interval: Duration): Int =
            (timeout / interval).toInt().coerceAtLeast(1)
    }

    private fun provider(setting: VideoGenerationProviderSetting): VideoGenerationProvider<*> =
        when (setting) {
            is VideoGenerationProviderSetting.Aliyun -> aliyun
            is VideoGenerationProviderSetting.Volcengine -> volcengine
            is VideoGenerationProviderSetting.MiniMax -> miniMax
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun VideoGenerationProvider<*>.createUnsafe(
        setting: VideoGenerationProviderSetting,
        request: VideoGenerationRequest,
    ): Result<VideoGenerationTask> =
        (this as VideoGenerationProvider<VideoGenerationProviderSetting>).create(setting, request)

    @Suppress("UNCHECKED_CAST")
    private suspend fun VideoGenerationProvider<*>.queryUnsafe(
        setting: VideoGenerationProviderSetting,
        taskId: String,
    ): Result<VideoGenerationTask> =
        (this as VideoGenerationProvider<VideoGenerationProviderSetting>).query(setting, taskId)
}
