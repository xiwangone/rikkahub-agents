package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防退化：工具错误输出必须走 [ToolErrors] 信封（JSON-RPC 2.0 形状），不得手写 `put("error", ...)`。
 *
 * 例外（非工具错误信封，不在本规则内）：
 * - [ToolErrors] 自身（信封的唯一实现）；
 * - 嵌套数据字段（如 batch 汇总中每项的 error、tried_hosts 数组元素）；
 * - 诊断/状态对象（如 MCP serverView、probe 结果）；
 * - 历史记录/运行摘要（如 workflow history）。
 *
 * 新增工具时如需返回错误，使用 `ToolErrors.envelopeFor(...)` /
 * `ToolErrors.partsFor(...)`，不要手写 buildJsonObject。
 */
class ToolErrorsEnvelopeGuardTest {

    /** 允许出現 `put("error"` 的白名单：文件名 -> 允许的最大行数（0 表示不允许）。 */
    private val allowlist = mapOf(
        // 信封的唯一实现
        "ToolErrors.kt" to Int.MAX_VALUE,
        // appendHumanErrorToToolResult 对旧扁平形状的兼容分支（输入侧，非新错误）
        "LocalTools.kt" to 2,
        // 嵌套数据：batch 汇总每项的 error
        "FileBatchTools.kt" to 1,
        // 嵌套数据：tried_hosts 数组元素的 error
        "SshHostsTool.kt" to 1,
        // 诊断对象：MCP server 状态视图的 error 字段
        "McpControlTools.kt" to 1,
        // 诊断对象：probe 结果的 error 字段
        "AppDiagnosticsTools.kt" to 1,
        // 运行摘要：subagent run 记录的 error 字段
        "SubAgentTools.kt" to 1,
        // 历史记录：workflow history 条目的 error 字段
        "WorkflowTools.kt" to 2,
    )

    @Test fun `no ad-hoc put("error") outside ToolErrors`() {
        val srcRoot = File("app/src/main/java")
        // 允许从仓库根目录运行（gradle test 的 workingDir 是模块目录）
        val roots = listOf(srcRoot, File("../app/src/main/java")).filter { it.isDirectory }
        assertTrue("source root not found", roots.isNotEmpty())

        val violations = mutableListOf<String>()
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val count = file.readLines().count { it.contains("""put("error"""") }
                    if (count == 0) return@forEach
                    val allowed = allowlist[file.name] ?: 0
                    if (count > allowed) {
                        violations += "${file.name}: found $count put(\"error\"), allowed $allowed"
                    }
                }
            if (violations.isNotEmpty()) break
        }
        assertTrue(
            "Ad-hoc put(\"error\") found outside ToolErrors — use ToolErrors.envelopeFor instead:\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test fun `envelope has JSON-RPC shape`() {
        val env = ToolErrors.envelopeFor(error = "invalid_argument", message = "bad")
        // code 为整数（JSON-RPC 要求），message 为字符串
        val code = env["code"]!!.toString().toIntOrNull()
        assertTrue("code must be an int", code != null)
        assertTrue("code must be negative JSON-RPC error code", code!! < 0)
        assertTrue(env["message"].toString().isNotBlank())
        // data.error 为业务码，data.recovery 三者之一
        val data = env["data"]!!.jsonObject
        assertTrue(data["error"].toString().contains("invalid_argument"))
        assertTrue(data["recovery"].toString().contains("fix_param"))
    }
}
