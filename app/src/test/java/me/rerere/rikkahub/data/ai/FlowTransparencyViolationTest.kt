package me.rerere.rikkahub.data.ai

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic diagnostics and retry-policy regression tests; no conversation data. */
class FlowTransparencyViolationTest {

    private fun transparencyViolation() = IllegalStateException(
        "Flow exception transparency is violated:\n" +
            "  Previous 'emit' call has thrown exception kotlinx.coroutines.JobCancellationException: " +
            "ScopeCoroutine is cancelling; job=ScopeCoroutine{Cancelled}@afb54bc, " +
            "but then emission attempt of value 'Retrying(...)' has been detected.\n" +
            "  Emissions from 'catch' blocks are prohibited in order to avoid unspecified behaviour, " +
            "'Flow.catch' operator can be used instead.",
    )

    @Test
    fun `透明度违规被识别`() =
        assertTrue(transparencyViolation().isFlowTransparencyViolation())

    @Test
    fun `普通 IllegalStateException 不误判`() =
        assertFalse(IllegalStateException("boom").isFlowTransparencyViolation())

    @Test
    fun `包装在 cause 链里的透明度违规也能识别`() =
        assertTrue(RuntimeException("wrapped", transparencyViolation()).isFlowTransparencyViolation())

    @Test
    fun `无 cause 链导致取消检查失配（bug 事实记录）`() {
        val failure = transparencyViolation()
        assertFalse(failure is kotlinx.coroutines.CancellationException)
        assertFalse(isCancellationFailure(failure))
    }

    @Test
    fun `透明度违规不进入重试`() =
        assertFalse(
            shouldRetryGenerationStreamFailure(
                failure = transparencyViolation(),
                retryAttempt = 0,
                maxRetries = 6,
                receivedMeaningfulOutput = false,
            ),
        )

    @Test
    fun `传输类失败仍然重试（排除规则不误伤）`() =
        assertTrue(
            shouldRetryGenerationStreamFailure(
                failure = IOException("Model stream closed without meaningful output"),
                retryAttempt = 0,
                maxRetries = 6,
                receivedMeaningfulOutput = false,
            ),
        )

    @Test
    fun `deeply wrapped cancellation is excluded from retry`() {
        var failure: Throwable = kotlinx.coroutines.CancellationException("synthetic cancellation")
        repeat(24) { failure = RuntimeException("synthetic wrapper", failure) }
        assertTrue(isCancellationFailure(failure))
        assertFalse(shouldRetryGenerationStreamFailure(failure, 0, 6, false))
    }

    @Test
    fun `cause cycles do not hang cancellation classification`() {
        val first = RuntimeException("synthetic first")
        val second = RuntimeException("synthetic second")
        first.initCause(second)
        second.initCause(first)
        assertFalse(isCancellationFailure(first))
    }
}
