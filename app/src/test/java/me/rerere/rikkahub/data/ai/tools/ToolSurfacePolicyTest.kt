package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the [ToolSurfacePolicy.decide] / [ToolSurfacePolicy.tierOf] contract: HOT stays HOT, cold families/extras stay COLD,
 * the assistant-level extraCold only ever DOWNGRADES (never upgrades), anything unknown is WARM, and every
decision reports **why** (TierSource) — so the read-only diagnosis can't drift from the real assembly.
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

    @Test
    fun `decide 标出助手级降温来源`() =
        assertEquals(
            TierSource.ASSISTANT_EXTRA_COLD,
            ToolSurfacePolicy.decide("workspace_shell", setOf("workspace_shell")).source,
        )

    @Test
    fun `decide 标出策略热档来源`() =
        assertEquals(TierSource.POLICY_HOT, ToolSurfacePolicy.decide("workspace_shell").source)

    @Test
    fun `decide 标出策略冷档单件来源`() =
        assertEquals(TierSource.POLICY_COLD_EXTRA, ToolSurfacePolicy.decide("appops_get").source)

    @Test
    fun `decide 标出策略冷档家族前缀来源`() =
        assertEquals(TierSource.POLICY_COLD_PREFIX, ToolSurfacePolicy.decide("telegram_send_message").source)

    @Test
    fun `decide 未命中规则时为默认温档`() =
        assertEquals(TierSource.DEFAULT_WARM, ToolSurfacePolicy.decide("some_new_tool").source)

    @Test
    fun `tierOf 与 decide 的档位一致`() =
        assertEquals(
            ToolSurfacePolicy.decide("telegram_send_message", setOf("telegram_send_message")).tier,
            ToolSurfacePolicy.tierOf("telegram_send_message", setOf("telegram_send_message")),
        )

    @Test
    fun `保命工具名单是零副作用自救层`() =
        assertEquals(
            setOf("list_tools", "get_tool_schema", "ask_user"),
            ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES,
        )
}
