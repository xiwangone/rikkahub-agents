package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardlineReasonCodeTest {
    @Test
    fun `known refusal messages have stable machine categories`() {
        val expected = mapOf(
            "delete the workspace/skills root" to "workspace_root_delete",
            "recursive delete of root filesystem" to "root_filesystem_delete",
            "recursive delete of home root" to "home_root_delete",
            "recursive delete of system directory" to "system_directory_delete",
            "recursive delete of home directory" to "home_directory_delete",
            "format filesystem (mkfs)" to "filesystem_format",
            "dd to raw block device" to "raw_block_device_dd",
            "redirect to raw block device" to "raw_block_device_redirect",
            "fork bomb" to "fork_bomb",
            "kill all processes" to "kill_all_processes",
            "system shutdown/reboot" to "system_shutdown_reboot",
            "init 0/6 (shutdown/reboot)" to "init_zero_six",
            "systemctl poweroff/reboot" to "systemctl_shutdown_reboot",
            "telinit 0/6 (shutdown/reboot)" to "telinit_zero_six",
            "encoded payload piped to shell" to "encoded_payload_to_shell",
            "hex-encoded payload piped to shell" to "hex_payload_to_shell",
            "eval of subshell command substitution" to "eval_command_substitution",
        )
        expected.forEach { (message, code) ->
            assertEquals(message, code, HardlineCommandGuard.reasonCodeForMessage(message))
        }
    }

    @Test
    fun `ui marker is reversible and does not change model facing text`() {
        val original = "blocked by safety floor (hardline): recursive delete of root filesystem. Stable model instruction."
        val encoded = HardlineCommandGuard.encodeUiReason(
            "recursive delete of root filesystem",
            original,
        )
        assertEquals("root_filesystem_delete", HardlineCommandGuard.uiReasonCodeFromEncoded(encoded))
        assertEquals(original, HardlineCommandGuard.modelFacingReason(encoded))
    }

    @Test
    fun `unknown dynamic refusal text remains unchanged`() {
        val original = "third-party tool returned: custom refusal 429"
        assertNull(HardlineCommandGuard.reasonCodeForMessage(original))
        assertEquals(original, HardlineCommandGuard.encodeUiReason(original, original))
        assertEquals(original, HardlineCommandGuard.modelFacingReason(original))
    }

    @Test
    fun `legacy browser hardline reason remains untranslated`() {
        val original = "hardline:js_cookie_write"
        assertNull(HardlineCommandGuard.reasonCodeForMessage(original))
        assertEquals(original, HardlineCommandGuard.encodeUiReason(original, original))
    }
}
