package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.HostAddressOverride
import me.rerere.rikkahub.data.network.parseFixedAddress
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.setting.DraftState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingNetworkHostOverridesPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val net = settings.networkSetting
    var hostOverrideDialogVisible by remember { mutableStateOf(false) }
    val hostOverrideDrafts = remember { DraftState(emptyList<HostAddressOverride>()) }
    var hostOverrideHostDraft by remember { mutableStateOf("") }
    var hostOverrideAddressDraft by remember { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun saveHostAddressOverrides(entries: List<HostAddressOverride>) {
        vm.updateSettings { current ->
            current.copy(
                networkSetting = current.networkSetting.copy(hostAddressOverrides = entries),
            )
        }
    }

    if (hostOverrideDialogVisible) {
        val canAdd =
            hostOverrideHostDraft.isNotBlank() &&
                parseFixedAddress(hostOverrideAddressDraft) != null
        AlertDialog(
            onDismissRequest = {
                hostOverrideDrafts.discard()
                hostOverrideDialogVisible = false
            },
            modifier = Modifier.imePadding(),
            title = {
                Text(stringResource(R.string.setting_page_preferences_network_host_overrides))
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hostOverrideDrafts.draft.isEmpty()) {
                        Text(
                            stringResource(
                                R.string.setting_page_preferences_network_host_overrides_empty,
                            )
                        )
                    }
                    hostOverrideDrafts.draft.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.host} → ${entry.address}",
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { checked ->
                                    hostOverrideDrafts.edit { current ->
                                        current.mapIndexed { i, item ->
                                            if (i == index) item.copy(enabled = checked) else item
                                        }
                                    }
                                },
                            )
                            IconButton(
                                onClick = {
                                    hostOverrideDrafts.edit { current ->
                                        current.filterIndexed { i, _ -> i != index }
                                    }
                                },
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = null)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = hostOverrideHostDraft,
                        onValueChange = { hostOverrideHostDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    R.string.setting_page_preferences_network_host_overrides_host,
                                )
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = hostOverrideAddressDraft,
                        onValueChange = { hostOverrideAddressDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                stringResource(
                                    R.string.setting_page_preferences_network_host_overrides_address,
                                )
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            if (hostOverrideAddressDraft.isNotBlank() &&
                                parseFixedAddress(hostOverrideAddressDraft) == null
                            ) {
                                Text(
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_invalid,
                                    )
                                )
                            }
                        },
                        isError = hostOverrideAddressDraft.isNotBlank() &&
                            parseFixedAddress(hostOverrideAddressDraft) == null,
                    )
                    TextButton(
                        onClick = {
                            hostOverrideDrafts.edit { current ->
                                current + HostAddressOverride(
                                    host = hostOverrideHostDraft.trim(),
                                    address = hostOverrideAddressDraft.trim(),
                                )
                            }
                            hostOverrideHostDraft = ""
                            hostOverrideAddressDraft = ""
                        },
                        enabled = canAdd,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            stringResource(
                                R.string.setting_page_preferences_network_host_overrides_add,
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveHostAddressOverrides(resolveHostOverridesToSave(hostOverrideDrafts.save()))
                        hostOverrideDialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hostOverrideDrafts.discard()
                        hostOverrideDialogVisible = false
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network_host_overrides)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val hostOverrides = net.hostAddressOverrides
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = {
                        Text(stringResource(R.string.setting_page_preferences_network_host_overrides))
                    },
                ) {
                    item(
                        onClick = {
                            hostOverrideDrafts.load(net.hostAddressOverrides)
                            hostOverrideHostDraft = ""
                            hostOverrideAddressDraft = ""
                            hostOverrideDialogVisible = true
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.ServerStack01,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_page_preferences_network_host_overrides_desc))
                        },
                        supportingContent = {
                            Text(
                                if (hostOverrides.isEmpty()) {
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_empty
                                    )
                                } else {
                                    stringResource(
                                        R.string.setting_page_preferences_network_host_overrides_count,
                                        hostOverrides.size,
                                    )
                                }
                            )
                        },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 主机覆写确认时保存的列表：直接保存草稿原样。
 *
 * 草稿在打开弹窗时已从已保存值同步初始化（见打开入口的赋值），弹窗渲染前
 * 草稿一定已就绪；空草稿只可能来自“用户删光了”或“本来就为空”，两种情况
 * 保存空列表都是对的。旧的“草稿为空就回退已保存值”兜底把“未初始化”与
 * “用户删光了”撞在了一起，删光操作会被悄悄丢掉。
 */
internal fun resolveHostOverridesToSave(
    drafts: List<HostAddressOverride>,
): List<HostAddressOverride> = drafts
