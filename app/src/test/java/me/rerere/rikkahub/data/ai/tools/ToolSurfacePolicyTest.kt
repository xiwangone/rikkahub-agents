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

    // ---- 白名单当“中间档”用（名单内照常判档，名单外降冷而非硬删）----

    @Test
    fun `白名单模式：名单内保持原档位`() =
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOfWithScope("workspace_shell", listOf("workspace_shell")),
        )

    @Test
    fun `白名单模式：名单外一律冷档（不硬删）`() {
        assertEquals(
            SurfaceTier.COLD,
            ToolSurfacePolicy.tierOfWithScope("web_fetch", listOf("workspace_shell")),
        )
        assertEquals(
            TierSource.OUTSIDE_ASSISTANT_SCOPE,
            ToolSurfacePolicy.tierWithScope("web_fetch", listOf("workspace_shell")).source,
        )
    }

    @Test
    fun `白名单模式：保命工具不受名单影响`() =
        assertEquals(
            SurfaceTier.WARM,
            ToolSurfacePolicy.tierOfWithScope("get_tool_schema", listOf("workspace_shell")),
        )

    @Test
    fun `白名单模式：名单为空时退回默认判档`() =
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOfWithScope("workspace_shell", emptyList()),
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

    // ---- UI 场景动态调整（WARM 档按场景升降）----

    @Test
    fun `场景 IMAGE_GEN 上调图片族为 HOT`() {
        // scan_media / set_wallpaper 静态在 COLD，图片页临时给完整 schema
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOf("scan_media", scene = UiScene.IMAGE_GEN),
        )
        assertEquals(
            TierSource.SCENE_RAISED,
            ToolSurfacePolicy.decide("scan_media", scene = UiScene.IMAGE_GEN).source,
        )
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOf("show_image", scene = UiScene.IMAGE_GEN),
        )
    }

    @Test
    fun `场景 IMAGE_GEN 下调无关热档为 WARM`() {
        // ssh / vault / 特权 / 设备诊断与图片生成无关，图片页临时收敛描述
        assertEquals(
            SurfaceTier.WARM,
            ToolSurfacePolicy.tierOf("ssh_exec_saved", scene = UiScene.IMAGE_GEN),
        )
        assertEquals(
            TierSource.SCENE_LOWERED,
            ToolSurfacePolicy.decide("ssh_exec_saved", scene = UiScene.IMAGE_GEN).source,
        )
    }

    @Test
    fun `场景不影响静态已 HOT 的来源标注`() {
        // 已在 HOT 的工具即使命中场景上调表也不改来源（避免来源失真）；
        // 此处用 show_image 反例：它静态 WARM，场景上调后来源应为 SCENE_RAISED（上一个测试已覆盖）
        assertEquals(
            TierSource.POLICY_HOT,
            ToolSurfacePolicy.decide("workspace_shell", scene = UiScene.IMAGE_GEN).source,
        )
    }

    @Test
    fun `场景下调不碰静态 COLD`() {
        // 冷档拦截是治理红线，场景无权解冻
        assertEquals(
            SurfaceTier.COLD,
            ToolSurfacePolicy.tierOf("telegram_send_message", scene = UiScene.IMAGE_GEN),
        )
    }

    @Test
    fun `UNKNOWN 场景回退到纯静态判定`() {
        assertEquals(
            ToolSurfacePolicy.tierOf("scan_media"),
            ToolSurfacePolicy.tierOf("scan_media", scene = UiScene.UNKNOWN),
        )
        assertEquals(
            ToolSurfacePolicy.tierOf("ssh_exec_saved"),
            ToolSurfacePolicy.tierOf("ssh_exec_saved", scene = UiScene.UNKNOWN),
        )
    }

    @Test
    fun `extraCold 优先级高于场景上调`() {
        // 用户显式配置不受场景影响
        assertEquals(
            SurfaceTier.COLD,
            ToolSurfacePolicy.tierOf("scan_media", setOf("scan_media"), UiScene.IMAGE_GEN),
        )
        assertEquals(
            TierSource.ASSISTANT_EXTRA_COLD,
            ToolSurfacePolicy.decide("scan_media", setOf("scan_media"), UiScene.IMAGE_GEN).source,
        )
    }

    @Test
    fun `白名单模式透传场景`() {
        assertEquals(
            SurfaceTier.HOT,
            ToolSurfacePolicy.tierOfWithScope(
                "scan_media",
                listOf("scan_media"),
                scene = UiScene.IMAGE_GEN,
            ),
        )
    }
}
