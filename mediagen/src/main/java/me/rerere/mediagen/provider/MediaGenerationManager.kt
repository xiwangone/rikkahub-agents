package me.rerere.mediagen.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.provider.providers.aliyun.AliyunMediaGenerationProvider
import me.rerere.mediagen.provider.providers.minimax.MiniMaxMediaGenerationProvider
import me.rerere.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider
import me.rerere.mediagen.provider.providers.openrouter.OpenRouterMediaGenerationProvider
import me.rerere.mediagen.provider.providers.volcengine.VolcengineMediaGenerationProvider
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class MediaGenerationManager(
    client: OkHttpClient = OkHttpClient(),
) {
    private val openAI = OpenAIMediaGenerationProvider(client)
    private val aliyun = AliyunMediaGenerationProvider(client)
    private val volcengine = VolcengineMediaGenerationProvider(client)
    private val miniMax = MiniMaxMediaGenerationProvider(client)
    private val openRouter = OpenRouterMediaGenerationProvider(client)

    suspend fun create(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = withContext(Dispatchers.Default) {
        unsupportedKind(setting, model) ?: provider(setting).createUnsafe(setting, model, request)
    }

    suspend fun query(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = withContext(Dispatchers.Default) {
        unsupportedKind(setting, model) ?: provider(setting).queryUnsafe(setting, model, taskId)
    }

    /**
     * 提交请求并跟踪到终态。同步返回结果的供应商只会发出一次终态任务；异步供应商先发出提交结果，
     * 再按 [interval] 轮询。Flow 被取消时，轮询也会立即停止。
     *
     * 轮询以 [timeout] 或 [maxAttempts] 为上限（先到为准），避免服务端一直返回非终态时永远
     * 轮询下去；默认上限与 app 侧 MediaCreationService.awaitTask 的口径一致。
     */
    fun generate(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
        interval: Duration = 15.seconds,
        timeout: Duration = DEFAULT_WATCH_TIMEOUT,
        maxAttempts: Int = maxWatchAttempts(timeout, interval),
    ): Flow<MediaGenerationTask> = flow {
        require(interval.isPositive()) { "interval must be positive" }
        val task = create(setting, model, request).getOrThrow()
        emit(task)
        if (task.isTerminal) return@flow
        delay(interval)
        emitAll(watch(setting, model, task.id, interval, timeout, maxAttempts))
    }

    /**
     * 轮询并依次发出服务端状态。Flow 被取消时，轮询也会立即停止。
     *
     * [timeout] 与 [maxAttempts] 共同封顶（先到为准）；连续 [MAX_UNKNOWN_FAILURES] 次
     * 收到 UNKNOWN 状态（不在终态集合内、服务端可能返回了未映射的新状态）视为任务卡住，
     * 直接失败而不是等到超时。
     */
    fun watch(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
        interval: Duration = 15.seconds,
        timeout: Duration = DEFAULT_WATCH_TIMEOUT,
        maxAttempts: Int = maxWatchAttempts(timeout, interval),
    ): Flow<MediaGenerationTask> = flow {
        require(interval.isPositive()) { "interval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        val startedAt = TimeSource.Monotonic.markNow()
        var attempts = 0
        var unknownFailures = 0
        while (true) {
            check(startedAt.elapsedNow() < timeout) {
                "Timed out waiting for media task $taskId after $timeout"
            }
            check(attempts < maxAttempts) {
                "Stopped polling media task $taskId after $maxAttempts attempts"
            }
            val task = query(setting, model, taskId).getOrThrow()
            attempts++
            emit(task)
            if (task.isTerminal) return@flow
            if (task.status == MediaGenerationStatus.UNKNOWN) {
                unknownFailures++
                if (unknownFailures >= MAX_UNKNOWN_FAILURES) {
                    throw IllegalStateException(
                        "Media task $taskId reported UNKNOWN status $unknownFailures times in a row; stopping poll",
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

    private fun provider(setting: MediaGenerationProviderSetting): MediaGenerationProvider<*> =
        when (setting) {
            is MediaGenerationProviderSetting.OpenAI -> openAI
            is MediaGenerationProviderSetting.Aliyun -> aliyun
            is MediaGenerationProviderSetting.Volcengine -> volcengine
            is MediaGenerationProviderSetting.MiniMax -> miniMax
            is MediaGenerationProviderSetting.OpenRouter -> openRouter
        }

    // 厂商适配器尚未实现该模型的 kind 时返回失败，否则返回 null
    private fun unsupportedKind(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
    ): Result<MediaGenerationTask>? {
        if (model.kind in setting.supportedKinds) return null
        return Result.failure(
            UnsupportedOperationException("${provider(setting).id} does not support ${model.kind} generation")
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun MediaGenerationProvider<*>.createUnsafe(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> =
        (this as MediaGenerationProvider<MediaGenerationProviderSetting>).create(setting, model, request)

    @Suppress("UNCHECKED_CAST")
    private suspend fun MediaGenerationProvider<*>.queryUnsafe(
        setting: MediaGenerationProviderSetting,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> =
        (this as MediaGenerationProvider<MediaGenerationProviderSetting>).query(setting, model, taskId)
}
