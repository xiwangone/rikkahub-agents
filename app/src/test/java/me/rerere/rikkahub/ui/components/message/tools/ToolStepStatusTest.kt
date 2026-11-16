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

    @Test
    fun `approved but not executed shows approved badge by scope`() {
        assertEquals(
            ToolStepStatus.APPROVED_ONCE,
            resolveToolStepStatus(false, false, false, null, null, false, approvedScope = "once"),
        )
        assertEquals(
            ToolStepStatus.APPROVED_CHAT,
            resolveToolStepStatus(false, false, false, null, null, false, approvedScope = "chat"),
        )
        assertEquals(
            ToolStepStatus.APPROVED_ALWAYS,
            resolveToolStepStatus(false, false, false, null, null, false, approvedScope = "always"),
        )
    }

    @Test
    fun `unknown approved scope falls back to once`() {
        assertEquals(
            ToolStepStatus.APPROVED_ONCE,
            resolveToolStepStatus(false, false, false, null, null, false, approvedScope = "bogus"),
        )
    }

    @Test
    fun `approved and executed follows execution result`() {
        assertEquals(
            ToolStepStatus.COMPLETED,
            resolveToolStepStatus(false, false, true, true, "0", false, approvedScope = "always"),
        )
        assertEquals(
            ToolStepStatus.FAILED,
            resolveToolStepStatus(false, false, true, false, "0", false, approvedScope = "once"),
        )
    }

    @Test
    fun `denied pending and interrupted still win over approved scope`() {
        assertEquals(
            ToolStepStatus.DENIED,
            resolveToolStepStatus(false, true, false, null, null, false, approvedScope = "always"),
        )
        assertEquals(
            ToolStepStatus.NEEDS_APPROVAL,
            resolveToolStepStatus(true, false, false, null, null, false, approvedScope = "always"),
        )
        assertEquals(
            ToolStepStatus.INTERRUPTED,
            resolveToolStepStatus(
                false,
                false,
                false,
                null,
                null,
                false,
                isInterruptedAttempt = true,
                approvedScope = "chat",
            ),
        )
    }
}
