package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompoundUiToolsTest {

    // ---- click_and_read ----

    @Test
    fun `click_and_read rejects bad by value`() {
        val tool = clickAndReadTool()
        val result = execTool(tool, """{"by":"class","value":"FooView"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_and_read rejects missing value`() {
        val tool = clickAndReadTool()
        val result = execTool(tool, """{"by":"text"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_and_read rejects negative nth`() {
        val tool = clickAndReadTool()
        val result = execTool(tool, """{"by":"text","value":"OK","nth":-1}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_and_read with a valid selector but offline service returns not-active`() {
        val tool = clickAndReadTool()
        val result = execTool(tool, """{"by":"text","value":"OK"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    // ---- wait_for ----

    @Test
    fun `wait_for rejects bad by value`() {
        val tool = waitForTool()
        val result = execTool(tool, """{"by":"class","value":"FooView"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `wait_for rejects missing value`() {
        val tool = waitForTool()
        val result = execTool(tool, """{"by":"text"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `wait_for rejects out-of-range timeout`() {
        val tool = waitForTool()
        val result = execTool(tool, """{"by":"text","value":"OK","timeout_ms":500}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `wait_for rejects out-of-range interval`() {
        val tool = waitForTool()
        val result = execTool(tool, """{"by":"text","value":"OK","interval_ms":50}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `wait_for with valid args but offline service returns not-active`() {
        val tool = waitForTool()
        val result = execTool(tool, """{"by":"text","value":"OK"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    // ---- withA11yDegradation (catch-narrowing contract) ----

    @Test
    fun `degradation guard passes regular results through`() {
        assertEquals(7L, withA11yDegradation("test", "probe") { 7L })
        assertNull(withA11yDegradation("test", "probe") { null })
    }

    @Test
    fun `degradation guard degrades IllegalStateException to null without rethrowing`() {
        val r = withA11yDegradation("test", "probe") {
            throw IllegalStateException("Accessibility in this instance has been closed")
        }
        assertNull("expected degradation to null", r)
    }

    @Test
    fun `degradation guard does not swallow unrelated runtime exceptions`() {
        try {
            withA11yDegradation("test", "probe") { throw IllegalArgumentException("real bug") }
            fail("expected IllegalArgumentException to propagate, got degraded null")
        } catch (e: IllegalArgumentException) {
            assertEquals("real bug", e.message)
        }
    }

    @Test
    fun `degradation guard does not swallow Errors`() {
        try {
            withA11yDegradation("test", "probe") { throw AssertionError("error-class must propagate") }
            fail("expected AssertionError to propagate, got degraded null")
        } catch (e: AssertionError) {
            assertEquals("error-class must propagate", e.message)
        }
    }
}
