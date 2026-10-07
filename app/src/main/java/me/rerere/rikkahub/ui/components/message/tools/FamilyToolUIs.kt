package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Bug01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock

/**
 * 「只改标题、摘要复用通用特征渲染」的族渲染器。
 *
 * 这批工具的输出形态各异、彼此不复用，但相比默认渲染器，它们真正缺的只是**标题说清在干什么**：
 * 摘要沿用默认那套「按输出特征渲染」（终端输出 / 列表 / 键值对 / 长文本），不再为每个工具
 * 重复实现一遍摘要逻辑。净效果：比默认渲染器少做一次全量特征分析，标题也可读。
 *
 * 加新工具：`internal val XxxToolUI = TitledToolUI("<工具名>", R.string.<标题>, HugeIcons.<图标>)`
 * 并在 [ToolUIRegistry] 注册；标题文案 7 语言一次补齐。
 */
internal class TitledToolUI(
    override val toolName: String,
    private val titleRes: Int,
    private val iconVector: ImageVector,
) : ToolUIRenderer {
    override fun icon(context: ToolUIContext): ImageVector = iconVector

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(titleRes)

    override fun hasSummary(context: ToolUIContext): Boolean = defaultSummaryText(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        defaultSummaryText(context)?.let { text ->
            HighlightCodeBlock(
                code = text,
                language = "text",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---- 凭证库（值一律不进 UI；摘要只呈现输出特征）----
internal val VaultCredentialNamesToolUI =
    TitledToolUI("vault_credential_names", R.string.tool_ui_vault_names, HugeIcons.Key01)

internal val VaultCredentialMetaToolUI =
    TitledToolUI("vault_credential_meta", R.string.tool_ui_vault_meta, HugeIcons.Settings03)

internal val VaultCredentialPrepareToolUI =
    TitledToolUI("vault_credential_prepare", R.string.tool_ui_vault_prepare, HugeIcons.Add01)

internal val VaultCredentialUpdateToolUI =
    TitledToolUI("vault_credential_update", R.string.tool_ui_vault_update, HugeIcons.Edit02)

internal val VaultExportEnvToolUI =
    TitledToolUI("vault_export_env", R.string.tool_ui_vault_export, HugeIcons.Bash)

internal val VaultHttpExecToolUI =
    TitledToolUI("vault_http_exec", R.string.tool_ui_vault_http, HugeIcons.GlobalSearch)

internal val VaultSshExecToolUI =
    TitledToolUI("vault_ssh_exec", R.string.tool_ui_vault_ssh, HugeIcons.ComputerTerminal01)

// ---- 诊断 / 元工具 ----
internal val DiagnosticsToolUI =
    TitledToolUI("diagnostics", R.string.tool_ui_diagnostics, HugeIcons.Bug01)

internal val GetToolSchemaToolUI =
    TitledToolUI("get_tool_schema", R.string.tool_ui_get_schema, HugeIcons.Code)

internal val CheckTokenUsageToolUI =
    TitledToolUI("check_token_usage", R.string.tool_ui_token_usage, HugeIcons.CoinsDollar)

internal val ListToolsToolUI =
    TitledToolUI("list_tools", R.string.tool_ui_list_tools, HugeIcons.CheckList)

internal val ToolSurfaceReportToolUI =
    TitledToolUI("tool_surface_report", R.string.tool_ui_surface_report, HugeIcons.ChartColumn)
