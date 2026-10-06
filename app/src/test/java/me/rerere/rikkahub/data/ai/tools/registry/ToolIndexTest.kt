package me.rerere.rikkahub.data.ai.tools.registry

import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具统一索引一致性自检：定义 / 注册 / 渲染三方对齐。
 *
 * 新增工具时必须在 [ToolIndex] 登记一行，否则以下测试失败
 * （“实现了却没注册”自动检查）。
 */
class ToolIndexTest {

    @Test
    fun `no duplicate tool names`() {
        val names = ToolIndex.entries.map { it.toolName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `all registered renderers are indexed with matching class name`() {
        for ((toolName, renderer) in ToolUIRegistry.rendererMap) {
            val entry = ToolIndex.find(toolName)
            assertNotNull("渲染器已注册但索引缺失: $toolName", entry)
            val declared = renderer::class.simpleName
            // 别名渲染器是匿名对象（simpleName 形如 `GestureToolUI$gestureAlias$1`），索引登记的是
            // 别名 val 名 —— 二者无法用类名对齐，此时只要求索引有一行登记。
            val isAlias = declared != null && declared.contains('$')
            assertTrue(
                "索引登记的渲染器与注册表不一致: $toolName (索引=${entry!!.renderer}, 注册表=$declared)",
                if (isAlias) !entry.renderer.isNullOrBlank() else entry.renderer == declared,
            )
        }
    }

    @Test
    fun `all indexed renderer claims are actually registered`() {
        val keys = ToolUIRegistry.registeredKeys
        for (entry in ToolIndex.withDedicatedRenderer()) {
            assertTrue(
                "索引声称有专用渲染器但注册表无此 key: ${entry.toolName}",
                entry.toolName in keys,
            )
        }
    }

    @Test
    fun `all catalog options are indexed`() {
        val indexedVia = ToolIndex.entries.map { it.registeredVia }.toSet()
        for (option in LocalToolCatalog.all) {
            assertTrue(
                "LocalToolOption 已存在但索引未登记其工具: ${option::class.simpleName}",
                "LocalToolOption.${option::class.simpleName}" in indexedVia,
            )
        }
    }

    @Test
    fun `unregistered tools are explicitly known`() {
        // 已定义但未接入注入路径：新增时必须人工复核后在此登记，禁止悄悄新增
        // 2026-10-06 整合：recent_chats/conversation_search 已恢复注入，web_extract 死代码已除名
        val expected = emptySet<String>()
        val actual = ToolIndex.unregistered().map { it.toolName }.toSet()
        assertEquals(
            "未注册工具清单变化，请复核后更新索引与本测试: 新增=${actual - expected} 减少=${expected - actual}",
            expected,
            actual,
        )
    }

    @Test
    fun `ask_user uses interactive invocation and default rendering`() {
        val entry = ToolIndex.find("ask_user")
        assertNotNull(entry)
        assertEquals(ToolIndex.InvocationKind.INTERACTIVE, entry!!.invocation)
        // 独立交互分支不进渲染框架
        assertEquals(null, entry.renderer)
    }

    @Test
    fun `index lookups work`() {
        val entry = ToolIndex.find("telegram_send_message")
        assertNotNull(entry)
        assertTrue(entry!!.definitionSite.contains("TelegramTool.kt"))
        assertEquals("LocalToolOption.TelegramBot", entry.registeredVia)
        assertEquals("TelegramSendToolUI", entry.renderer)

        assertTrue(ToolIndex.byRegistration("LocalToolOption.TelegramBot").size >= 14)
        assertTrue(ToolIndex.withDedicatedRenderer().isNotEmpty())
        assertTrue(ToolIndex.defaultRendered().isNotEmpty())
    }

    @Test
    fun `tier matches policy decision`() {
        // 索引档位必须与 ToolSurfacePolicy 判定一致（单一来源，防漂移）
        for (entry in ToolIndex.entries) {
            assertEquals(
                "tier mismatch for ${entry.toolName}",
                ToolSurfacePolicy.tierOf(entry.toolName),
                entry.tier,
            )
        }
        // 三档都有覆盖
        assertTrue(ToolIndex.byTier(SurfaceTier.HOT).isNotEmpty())
        assertTrue(ToolIndex.byTier(SurfaceTier.WARM).isNotEmpty())
        assertTrue(ToolIndex.byTier(SurfaceTier.COLD).isNotEmpty())
    }
}
