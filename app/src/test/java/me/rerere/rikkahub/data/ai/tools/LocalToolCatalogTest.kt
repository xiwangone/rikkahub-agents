package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [LocalToolCatalog.capabilityOf]: tools that need a system service must declare it,
 * otherwise the assembly gate ([LocalTools.getTools]) injects them even when the service
 * is off and the model burns a turn on a call that cannot succeed.
 *
 * Regression: PhoneUse used to fall through to [ToolCapability.NONE] — the single
 * accessibility service was off but phone_use was still injected, and the
 * diagnostics(kind="tool_scope") could not explain why it was not ready.
 */
class LocalToolCatalogTest {

    @Test
    fun `PhoneUse requires ACCESSIBILITY`() =
        assertEquals(ToolCapability.ACCESSIBILITY, LocalToolCatalog.capabilityOf(LocalToolOption.PhoneUse))

    @Test
    fun `ScreenAutomation requires ACCESSIBILITY`() =
        assertEquals(ToolCapability.ACCESSIBILITY, LocalToolCatalog.capabilityOf(LocalToolOption.ScreenAutomation))

    @Test
    fun `Shizuku requires SHIZUKU`() =
        assertEquals(ToolCapability.SHIZUKU, LocalToolCatalog.capabilityOf(LocalToolOption.Shizuku))

    @Test
    fun `Termux and SpeechToText require TERMUX`() {
        assertEquals(ToolCapability.TERMUX, LocalToolCatalog.capabilityOf(LocalToolOption.Termux))
        assertEquals(ToolCapability.TERMUX, LocalToolCatalog.capabilityOf(LocalToolOption.SpeechToText))
    }

    @Test
    fun `plain tools require nothing`() =
        assertEquals(ToolCapability.NONE, LocalToolCatalog.capabilityOf(LocalToolOption.WebFetch))
}
