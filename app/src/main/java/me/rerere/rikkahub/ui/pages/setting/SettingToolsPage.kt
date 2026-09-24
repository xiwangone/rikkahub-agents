package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.setting.ToolOutputDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.termux.SettingTermuxViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * 「工具」设置页：把原本分散的工具相关项收拢到一处。
 *
 * - 工具详略（原「界面偏好」的精简工具说明）
 * - 工具输出上限（原在对话输入面板里，属全局项错位）
 * - 工具名单（默认关闭；黑名单=只发简要说明 / 白名单=只注入名单内，含一键恢复默认）
 * - 每轮对话时间预算（原在「Termux」页里，属全局项错位）
 * - 工具审批入口（原在「安全」分组）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingToolsPage(
    vm: SettingVM = koinViewModel(),
    termuxVm: SettingTermuxViewModel = koinViewModel(),
) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val termuxConfig by termuxVm.config.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    var showOutputDialog by remember { mutableStateOf(false) }
    var budgetDraft by remember(termuxConfig.turnBudgetMs) {
        mutableStateOf((termuxConfig.turnBudgetMs / 60_000L).toString())
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_page_tools)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_detail)) }) {
                item(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_tool_surface_trim_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_tool_surface_trim_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.toolSurfaceTrimming,
                            onCheckedChange = { on ->
                                vm.updateSettings(
                                    settings.copy(
                                        displaySetting = displaySetting.copy(toolSurfaceTrimming = on),
                                    ),
                                )
                            },
                        )
                    },
                )
                item(
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_tool_stats_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_tool_stats_desc))
                    },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.toolStatsEnabled,
                            onCheckedChange = { on ->
                                vm.updateSettings(
                                    settings.copy(
                                        displaySetting = displaySetting.copy(toolStatsEnabled = on),
                                    ),
                                )
                            },
                        )
                    },
                )
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_output)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_model_page_tool_output)) },
                    supportingContent = { Text(stringResource(R.string.setting_model_page_tool_output_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.toolOutputEnabled,
                            onCheckedChange = { on ->
                                vm.updateSettings(settings.copy(toolOutputEnabled = on))
                            },
                        )
                    },
                    modifier = Modifier.clickable { showOutputDialog = true },
                )
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_advanced)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_termux_turn_budget)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_turn_budget_desc)) },
                    trailingContent = {
                        OutlinedTextField(
                            value = budgetDraft,
                            onValueChange = { input ->
                                budgetDraft = input.filter { it.isDigit() }.take(3)
                            },
                            label = { Text(stringResource(R.string.setting_termux_unit_minutes)) },
                            singleLine = true,
                            modifier = Modifier.width(120.dp),
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.setting_tools_apply_budget)) },
                    supportingContent = { Text(stringResource(R.string.setting_tools_apply_budget_desc)) },
                    modifier =
                        Modifier.clickable {
                            budgetDraft.toLongOrNull()?.let { termuxVm.setTurnBudgetMinutes(it) }
                        },
                )
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_approval)) }) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_tool_approvals)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_tool_approvals_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                    modifier = Modifier.clickable { navController.navigate(Screen.SettingToolApprovals) },
                )
            }
        }
    }

    if (showOutputDialog) {
        ToolOutputDialog(
            enabled = settings.toolOutputEnabled,
            maxCharsKB = settings.toolOutputMaxChars / 1000,
            compactMaxCharsKB = settings.toolOutputCompactMaxChars / 1000,
            onDismiss = { showOutputDialog = false },
            onConfirm = { enabled, kb, compactKb ->
                vm.updateSettings(
                    settings.copy(
                        toolOutputEnabled = enabled,
                        toolOutputMaxChars = kb * 1000,
                        toolOutputCompactMaxChars = compactKb * 1000,
                    ),
                )
            },
        )
    }

}

