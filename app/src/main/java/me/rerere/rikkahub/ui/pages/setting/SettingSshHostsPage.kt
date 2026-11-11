package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

/**
 * SSH 主机管理页（Material 3 Expressive 分组卡片样式）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSshHostsPage() {
    val sshHostRepository: SshHostRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val vaultRepo: me.rerere.rikkahub.data.vault.CredentialVaultRepository = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    // Format strings resolved configuration-aware at composition time; the
    // dynamic argument is substituted at the call site so the message stays current.
    val loadFailedFormat = stringResource(R.string.setting_ssh_hosts_load_failed)
    val saveFailedFormat = stringResource(R.string.ssh_hosts_save_failed)
    val hostDeletedFormat = stringResource(R.string.setting_ssh_host_deleted)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var hosts by remember { mutableStateOf<List<SshHostEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<SshHostEntity?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SshHostEntity?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            sshHostRepository.getAll()
        }.onSuccess { list ->
            AppLog.d("SshHostsPage", "加载主机列表: ${list.size} 个 ${list.map { it.name }}")
            hosts = list
            loadError = null
        }.onFailure { e ->
            AppLog.w("SshHostsPage", "加载主机列表失败: ${e.message}")
            loadError = e.message ?: e.javaClass.simpleName
            toaster.show(loadFailedFormat.format(loadError!!))
        }
    }

    fun reload() {
        scope.launch {
            runCatching {
                sshHostRepository.getAll()
            }.onSuccess { list ->
                AppLog.d("SshHostsPage", "刷新主机列表: ${list.size} 个")
                hosts = list
                loadError = null
            }.onFailure { e ->
                AppLog.w("SshHostsPage", "刷新主机列表失败: ${e.message}")
                loadError = e.message ?: e.javaClass.simpleName
                toaster.show(loadFailedFormat.format(loadError!!))
            }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            editing = null
                            showEdit = true
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = stringResource(R.string.setting_ssh_host_add),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (loadError != null) {
                item("sshLoadError") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_ssh_hosts_load_failed, loadError!!),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    loadError = null
                                    reload()
                                },
                            ) {
                                Text(stringResource(R.string.common_retry))
                            }
                        }
                    }
                }
            }

            item("sshDefaultPlatform") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_ssh_default_platform),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SshPlatform.entries.forEachIndexed { index, platform ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = SshPlatform.entries.size),
                                selected = settings.defaultSshPlatform == platform,
                                onClick = {
                                    scope.launch {
                                        settingsStore.update { it.copy(defaultSshPlatform = platform) }
                                    }
                                },
                            ) {
                                Text(stringResource(sshPlatformLabelRes(platform)))
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.setting_ssh_default_platform_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            item("sshHostsGroup") {
                // ⚠ CardGroup 内部用 `remember { CardGroupScopeImpl() }` 缓存条目列表，而该列表不是 State：
                // 列表数据（hosts）从空加载成 N 个时，LazyColumn 的 item 不保证会让 CardGroup 的 scope 重建，
                // 表现为“数据已加载（日志可见）但页面空白、新加主机也不显示”。用 key(hosts) 强制重建。
                key(hosts) {
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_ssh_hosts_title)) },
                    ) {
                        hosts.forEach { host ->
                        item(
                            onClick = {
                                editing = host
                                showEdit = true
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                            headlineContent = { Text(host.name) },
                            supportingContent = {
                                Text(
                                    text = "${host.user}@${host.host}:${host.port}",
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            navController.navigate(Screen.SshTerminal(host.name))
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.ComputerTerminal01,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.setting_ssh_host_connect),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteTarget = host },
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.setting_ssh_host_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    item(
                        onClick = {
                            editing = null
                            showEdit = true
                        },
                        leadingContent = {
                            CardGroupIcon(
                                icon = HugeIcons.Add01,
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        },
                        headlineContent = {
                            Text(
                                stringResource(R.string.setting_ssh_host_add),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                }
            }

            item("sshHint") {
                Text(
                    text = stringResource(R.string.setting_ssh_host_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    if (showEdit) {
        SshHostEditDialog(
            initial = editing,
            vaultRepo = vaultRepo,
            onDismiss = { showEdit = false },
            onSave = { entity ->
                scope.launch {
                    runCatching {
                        sshHostRepository.upsert(entity)
                    }.onSuccess {
                        reload()
                        showEdit = false
                    }.onFailure { e ->
                        AppLog.w(
                            "SshHostsPage",
                            "failed to save SSH host: name=${entity.name} err=${e.message}",
                        )
                        toaster.show(saveFailedFormat.format(e.message ?: e.javaClass.simpleName))
                    }
                }
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.setting_ssh_host_delete_title),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            val target = deleteTarget
            deleteTarget = null
            if (target != null) {
                scope.launch {
                    sshHostRepository.deleteByName(target.name)
                    reload()
                    toaster.show(hostDeletedFormat.format(target.name))
                }
            }
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, deleteTarget?.name ?: ""))
    }
}
