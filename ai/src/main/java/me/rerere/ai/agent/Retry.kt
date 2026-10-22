package me.rerere.ai.agent

import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 请求模型失败时的重试策略：最多重试 [maxRetries] 次。
 *
 * 默认只重试 [IOException]，也就是连不上、超时、读到一半断开这类网络故障；
 * [nonRetryable] 里的类型即使命中 [retryOn] 也不会重放请求，用于把「响应已收到、但下游处理失败」
 * 这类不能重发模型请求的情况固定下来。
 * 等待时长按 [initialDelay] 指数递增（1s、2s、4s…）；调用方需要自定义节奏时给 [delayFor]，
 * 非空即完全接管第几次重试等多久，[initialDelay] 不再生效。
 */
data class RetryPolicy(
    val maxRetries: Int,
    val initialDelay: Duration = 1.seconds,
    val retryOn: (Throwable) -> Boolean = { it is IOException },
    val nonRetryable: (Throwable) -> Boolean = { false },
    val delayFor: ((retry: Int) -> Duration)? = null,
) {
    companion object {
        val NONE = RetryPolicy(maxRetries = 0)

        /** 网络自动重试：最多 3 次，间隔 1s、2s、4s */
        val NETWORK = RetryPolicy(maxRetries = 3)
    }
}

/** 第 [retry] 次重试（共 [maxRetries] 次）将在 [delay] 后开始，[cause] 是上一次失败的原因 */
data class RetryAttempt(
    val cause: Throwable,
    val retry: Int,
    val maxRetries: Int,
    val delay: Duration,
)

/**
 * 按 [policy] 执行 [block]：失败且允许重试时先调用 [onRetry]，等待后再执行一次。
 *
 * 有意 catch Throwable：任何失败都交给 policy 决定是否重试；取消经 ensureActive() 与类型判断双重排除。
 * 四个终态（取消 / 次数用尽 / 策略拒绝 / 继续重试）写在同一处条件里，是为了一致地处理“抛还是重试”。
 *
 * [onRetry] is outside the request block: a failed notification aborts retry and
 * propagates unchanged. In particular, never swallow cancellation, Error, or delivery failures.
 */
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ComplexCondition")
suspend fun <T> withRetry(
    policy: RetryPolicy,
    onRetry: suspend (RetryAttempt) -> Unit = {},
    block: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return block()
        } catch (error: Throwable) {
            // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
            // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
            currentCoroutineContext().ensureActive()
            if (error is Error || error.hasCancellationCause() || retries >= policy.maxRetries ||
                policy.nonRetryable(error) || !policy.retryOn(error)
            ) {
                throw error
            }
            retries++
            val backoff = policy.delayFor?.invoke(retries) ?: policy.initialDelay * (1 shl (retries - 1))
            onRetry(RetryAttempt(error, retries, policy.maxRetries, backoff))
            delay(backoff)
        }
    }
}

/** Inspect causes without losing the original wrapper or looping on malformed cause cycles. */
internal fun Throwable.hasCancellationCause(): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is CancellationException) return true
        current = current.cause
    }
    return false
}
