package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.shellToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace_shell 的 description 参数：
 *
 * - schema 里有可选的 description（string），模型按它写 5-10 词的步骤说明；
 * - 标题优先用 description，没有/空白时回落到原命令预览逻辑；
 * - 有 description 时摘要必须出现（审批时命令要在摘要里可见），无 description 且
 *   无输出时摘要不出现（保持旧行为）。
 *
 * description 的取数是纯函数（[shellDescriptionOf]），这里直接断言它；
 * title() 本身是 @Composable，取数逻辑抽出来测、组合由编译保证。
 */
class ShellToolUITest {

    private fun contextFor(
        arguments: JsonObject = JsonObject(emptyMap()),
        content: JsonObject? = null,
    ) = ToolUIContext(
        tool = UIMessagePart.Tool(toolCallId = "test", toolName = "workspace_shell", input = "{}"),
        arguments = arguments,
        content = content,
        loading = false,
    )

    @Test
    fun `schema contains optional description parameter`() {
        val schema = shellToolSchema(defaultCwd = null, presetsPath = "/presets")
        val description = schema["description"]?.jsonObject
        assertTrue("schema 缺 description 参数", description != null)
        assertEquals("string", description?.get("type")?.jsonPrimitive?.content)
        val wording = description?.get("description")?.jsonPrimitive?.content.orEmpty()
        assertTrue("schema 文案须要求 5-10 词", "5-10 words" in wording)
        assertTrue("schema 文案须要求用户语言", "user's language" in wording)
    }

    @Test
    fun `description missing returns null`() {
        assertNull(shellDescriptionOf(buildJsonObject { put("command", "ls") }))
    }

    @Test
    fun `description empty returns null`() {
        assertNull(shellDescriptionOf(buildJsonObject { put("description", "") }))
    }

    @Test
    fun `description blank returns null`() {
        assertNull(shellDescriptionOf(buildJsonObject { put("description", "   \n  ") }))
    }

    @Test
    fun `description is trimmed`() {
        assertEquals(
            "列出工作区文件",
            shellDescriptionOf(buildJsonObject { put("description", "  列出工作区文件\n") }),
        )
    }

    @Test
    fun `hasSummary false without content and description`() {
        assertFalse(ShellToolUI.hasSummary(contextFor()))
    }

    @Test
    fun `hasSummary true with description and no content`() {
        val arguments = buildJsonObject {
            put("command", "ls -la")
            put("description", "列出工作区文件")
        }
        assertTrue(ShellToolUI.hasSummary(contextFor(arguments = arguments)))
    }

    @Test
    fun `hasSummary true with content and no description`() {
        val content = buildJsonObject { put("stdout", "ok") }
        assertTrue(ShellToolUI.hasSummary(contextFor(content = content)))
    }

    @Test
    fun `hasSummary true with both content and description`() {
        val arguments = buildJsonObject {
            put("command", "ls -la")
            put("description", "列出工作区文件")
        }
        val content = buildJsonObject { put("stdout", "ok") }
        assertTrue(ShellToolUI.hasSummary(contextFor(arguments = arguments, content = content)))
    }
}
