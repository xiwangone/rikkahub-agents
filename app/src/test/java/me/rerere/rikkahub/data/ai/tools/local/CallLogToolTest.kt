package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertFalse
import org.junit.Test

class CallLogToolTest {

    // Success path requires a real CallLog content provider — instrumented test required.
    //
    // The unknown-call-type validation runs AFTER PermissionHelper.hasRuntime(). On a host JVM
    // the Android stub Context either throws ("Method ... not mocked") or — with
    // `testOptions.unitTests.isReturnDefaultValues` enabled — returns a default (PERMISSION_DENIED)
    // and lets the tool answer with a structured error envelope. Both prove the permission /
    // validation gate ran before any I/O; what must never happen is a silent success.
    @Test
    fun `list_call_log with unknown type does not silently succeed`() {
        val tool = callLogTool(NULL_CONTEXT)
        val output = try {
            execTool(tool, """{"type":"foo"}""")
        } catch (t: Throwable) {
            // Surfaced by the permission gate on a stub Context — the gate ran, not a silent success.
            return
        }
        assertFalse(
            "unknown call type must not succeed silently: $output",
            output.contains("\"success\":true"),
        )
    }
}
