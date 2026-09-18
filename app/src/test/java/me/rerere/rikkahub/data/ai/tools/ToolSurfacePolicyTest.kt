package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [ToolSurfacePolicy.tierOf] contract: HOT stays HOT, cold families/extras stay COLD,
 * the assistant-level extraCold only ever DOWNGRADES (never upgrades), and anything unknown is WARM.
 * Keeping this stable matters because the injected tool set feeds the long-context prefix cache.
 */
class ToolSurfacePolicyTest {

    @Test
    fun `HOT 工具返回 HOT`() =
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("workspace_shell"))

    @Test
    fun `冷家族前缀命中 COLD`() =
        assertEquals(SurfaceTier.COLD, ToolSurfacePolicy.tierOf("telegram_send_message"))

    @Test
    fun `冷单件命中 COLD`() =
        assertEquals(SurfaceTier.COLD, ToolSurfacePolicy.tierOf("appops_get"))

    @Test
    fun `未知工具默认 WARM`() =
        assertEquals(SurfaceTier.WARM, ToolSurfacePolicy.tierOf("some_new_tool"))

    @Test
    fun `extraCold 把 HOT 下调为 COLD`() =
        assertEquals(SurfaceTier.COLD, ToolSurfacePolicy.tierOf("workspace_shell", setOf("workspace_shell")))

    @Test
    fun `extraCold 不含的工具不受影响`() =
        assertEquals(SurfaceTier.HOT, ToolSurfacePolicy.tierOf("workspace_shell", setOf("ssh_exec_saved")))
}
