package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.ui.pages.assistant.detail.colorForLocalToolOption
import me.rerere.rikkahub.ui.pages.assistant.detail.iconForLocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标着色覆盖自检：110 个 ToolUI 渲染器与 59 个 LocalToolOption 必须全部落在
 * 8 个家族色组内（不回退默认单色），且 LocalToolOption 的图标在去重后互不相同。
 *
 * 颜色决策是纯函数（[ToolUIRenderer.iconColorKey] / [colorForLocalToolOption]），
 * 这里直接断言色组；色组到 ExtendColors 档位的解析（[ToolIconColor.resolve]）只有
 * 8 行映射，由彩色预览图目视核对。
 *
 * 新增工具/选项时：渲染器必须 override iconColorKey（家族基类 override 一次即可），
 * LocalToolOption 必须在 colorForLocalToolOption 里登记，否则以下测试失败。
 */
class ToolIconColorTest {

    private fun contextFor(
        toolName: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(toolCallId = "test", toolName = toolName, input = "{}"),
        arguments = arguments,
        content = null,
        loading = false,
    )

    private val familyGroups = ToolIconColor.entries.filter { it != ToolIconColor.DEFAULT }.toSet()

    @Test
    fun `all registered renderers have a family color`() {
        assertEquals(111, ToolUIRegistry.rendererMap.size)
        for ((toolName, renderer) in ToolUIRegistry.rendererMap) {
            val key = renderer.iconColorKey(contextFor(toolName))
            assertTrue("渲染器无家族色（回退默认单色）: $toolName", key in familyGroups)
        }
    }

    @Test
    fun `dangerous tools are red across families`() {
        val red =
            setOf(
                "delete_file",
                "batch_delete",
                "telegram_delete_commands",
                "telegram_disable",
                "workspace_background_kill",
                "termux_session_kill",
            )
        for (toolName in red) {
            val renderer = ToolUIRegistry.resolve(toolName)
            assertEquals(toolName, ToolIconColor.RED, renderer.iconColorKey(contextFor(toolName)))
        }
    }

    @Test
    fun `memory_tool delete action is red, other actions are purple`() {
        val renderer = ToolUIRegistry.resolve("memory_tool")
        val deleteArgs = buildJsonObject { put("action", "delete") }
        assertEquals(ToolIconColor.RED, renderer.iconColorKey(contextFor("memory_tool", deleteArgs)))
        assertEquals(ToolIconColor.PURPLE, renderer.iconColorKey(contextFor("memory_tool")))
    }

    @Test
    fun `all local tool options have a family color`() {
        val all = LocalToolCatalog.all
        assertEquals(60, all.size)
        for (option in all) {
            val key = colorForLocalToolOption(option)
            assertTrue("本地工具选项无家族色: ${option::class.simpleName}", key in familyGroups)
        }
    }

    @Test
    fun `all local tool option icons are distinct`() {
        val all = LocalToolCatalog.all
        val icons = all.map { iconForLocalToolOption(it) }
        assertEquals(icons.size, icons.toSet().size)
    }
}
