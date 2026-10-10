package me.rerere.rikkahub.ui.components.message.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStepStatusTest {
    @Test
    fun `denial and approval state take precedence over execution result`() {
        assertEquals(
            ToolStepStatus.DENIED,
            resolveToolStepStatus(
                isPending = true,
                isDenied = true,
                isExecuted = true,
                success = true,
                exitCode = "0",
                hasError = false,
            ),
        )
        assertEquals(
            ToolStepStatus.NEEDS_APPROVAL,
            resolveToolStepStatus(
                isPending = true,
                isDenied = false,
                isExecuted = false,
                success = null,
                exitCode = null,
                hasError = false,
            ),
        )
    }

    @Test
    fun `unexecuted tool is running`() {
        assertEquals(
            ToolStepStatus.RUNNING,
            resolveToolStepStatus(
                isPending = false,
                isDenied = false,
                isExecuted = false,
                success = null,
                exitCode = null,
                hasError = false,
            ),
        )
    }

    @Test
    fun `explicit failure evidence marks completed tool as failed`() {
        val failures =
            listOf(
                Triple(false, "0", false),
                Triple(true, "2", false),
                Triple(true, "0", true),
            )
        failures.forEach { (success, exitCode, hasError) ->
            assertEquals(
                ToolStepStatus.FAILED,
                resolveToolStepStatus(
                    isPending = false,
                    isDenied = false,
                    isExecuted = true,
                    success = success,
                    exitCode = exitCode,
                    hasError = hasError,
                ),
            )
        }
    }

    @Test
    fun `executed result without failure evidence is completed`() {
        assertEquals(
            ToolStepStatus.COMPLETED,
            resolveToolStepStatus(
                isPending = false,
                isDenied = false,
                isExecuted = true,
                success = null,
                exitCode = null,
                hasError = false,
            ),
        )
    }
}
