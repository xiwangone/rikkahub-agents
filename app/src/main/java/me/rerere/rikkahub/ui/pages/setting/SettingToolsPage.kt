package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.TextWrap
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.ToolOutputDialog
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.termux.SettingTermuxViewModel
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

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
    val currentBudgetMinutes = termuxConfig.turnBudgetMs / 60_000L
    var budgetDraft by remember(currentBudgetMinutes) {
        mutableStateOf(currentBudgetMinutes.toString())
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_tools)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_detail)) }) {
                switchItem(
                    checked = displaySetting.toolSurfaceTrimming,
                    onCheckedChange = { on ->
                        vm.updateSettings { current ->
                            current.copy(
                                displaySetting = current.displaySetting.copy(toolSurfaceTrimming = on),
                            )
                        }
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Wrench01,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_tool_surface_trim_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_tool_surface_trim_desc))
                    },
                )
                switchItem(
                    checked = displaySetting.toolStatsEnabled,
                    onCheckedChange = { on ->
                        vm.updateSettings { current ->
                            current.copy(
                                displaySetting = current.displaySetting.copy(toolStatsEnabled = on),
                            )
                        }
                    },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.DashboardSpeed02,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    headlineContent = {
                        Text(stringResource(R.string.setting_display_page_tool_stats_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.setting_display_page_tool_stats_desc))
                    },
                )
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_output)) }) {
                item(
                    onClick = { showOutputDialog = true },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.TextWrap,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.setting_model_page_tool_output)) },
                    supportingContent = { Text(stringResource(R.string.setting_model_page_tool_output_desc)) },
                    trailingContent = {
                        Switch(
                            checked = settings.toolOutputEnabled,
                            onCheckedChange = { on ->
                                vm.updateSettings { current -> current.copy(toolOutputEnabled = on) }
                            },
                        )
                    },
                )
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_advanced)) }) {
                formItem(
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Clock01,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.setting_termux_turn_budget)) },
                    supportingContent = { Text(stringResource(R.string.setting_termux_turn_budget_desc)) },
                    trailingContent = {
                        CardGroupValue("$currentBudgetMinutes ${stringResource(R.string.setting_termux_unit_minutes)}")
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = budgetDraft,
                            onValueChange = { input ->
                                budgetDraft = input.filter { it.isDigit() }.take(3)
                            },
                            label = { Text(stringResource(R.string.setting_termux_unit_minutes)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = {
                                budgetDraft.toLongOrNull()?.let { termuxVm.setTurnBudgetMinutes(it) }
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.CheckmarkCircle02,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.setting_tools_apply_budget))
                        }
                    }
                }
            }

            CardGroup(title = { Text(stringResource(R.string.setting_tools_section_approval)) }) {
                item(
                    onClick = { navController.navigate(Screen.SettingToolApprovals) },
                    leadingContent = {
                        CardGroupIcon(
                            icon = HugeIcons.Shield01,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.setting_page_tool_approvals)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_tool_approvals_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
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
                vm.updateSettings { current ->
                    current.copy(
                        toolOutputEnabled = enabled,
                        toolOutputMaxChars = kb * 1000,
                        toolOutputCompactMaxChars = compactKb * 1000,
                    )
                }
            },
        )
    }
}
