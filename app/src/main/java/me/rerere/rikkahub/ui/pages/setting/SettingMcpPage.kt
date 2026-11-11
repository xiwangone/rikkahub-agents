package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import android.content.Intent
import me.rerere.rikkahub.service.LocalMcpServerService
import androidx.compose.material3.Scaffold
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaDeleteConfirmDialog
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val creationState =
        useEditState<McpServerConfig> {
            vm.updateSettings { current ->
            current.copy(
                    mcpServers = current.mcpServers + it,
                )
        }
        }
    val editState =
        useEditState<McpServerConfig> { newConfig ->
            vm.updateSettings { current ->
            current.copy(
                    mcpServers =
                        current.mcpServers.map {
                            if (it.id == newConfig.id) {
                                newConfig
                            } else {
                                it
                            }
                        },
                )
        }
        }
    val localMcpProfiles = settings.localMcpProfiles
    var showProfileDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<LocalMcpProfile?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        },
                    ) {
                        Icon(HugeIcons.FileImport, stringResource(R.string.accessibility_import))
                    }
                    IconButton(
                        onClick = {
                            creationState.open(McpServerConfig.StreamableHTTPServer())
                        },
                    ) {
                        Icon(HugeIcons.Add01, stringResource(R.string.add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding =
                    PaddingValues(
                        start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                        top = innerPadding.calculateTopPadding() + 16.dp,
                        end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
            ) {
                item(key = "local_mcp") {
                    val ctx = LocalContext.current
                    LocalMcpServerSection(
                        profiles = localMcpProfiles,
                        activeId = settings.activeLocalMcpProfileId,
                        enabled = settings.localMcpServerEnabled,
                        onEnable = { en ->
                            ctx.startService(
                                Intent(ctx, LocalMcpServerService::class.java).apply {
                                    action = if (en) LocalMcpServerService.ACTION_START else LocalMcpServerService.ACTION_STOP
                                },
                            )
                            vm.updateSettings { current -> current.copy(localMcpServerEnabled = en) }
                        },
                        onAdd = { editingProfile = LocalMcpProfile(id = "local-mcp-${System.currentTimeMillis()}", name = ""); showProfileDialog = true },
                        onEdit = { p -> editingProfile = p; showProfileDialog = true },
                        onDelete = { p -> vm.updateSettings { current ->
            current.copy(localMcpProfiles = current.localMcpProfiles.filter { it.id != p.id })
        } },
                        onSetActive = { p -> vm.updateSettings { current ->
            current.copy(activeLocalMcpProfileId = p.id)
        } },
                    )
                }

                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        onEdit = {
                            editState.open(mcpConfig)
                        },
                        onDelete = {
                            vm.updateSettings { current ->
            current.copy(
                                    mcpServers = current.mcpServers.filter { it.id != mcpConfig.id },
                                )
        }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                ExtensionEmptyState(
                    message = stringResource(R.string.setting_mcp_page_no_mcp_servers_found),
                    description = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                    buttonText = stringResource(R.string.add),
                    onAction = {
                        creationState.open(McpServerConfig.StreamableHTTPServer())
                    },
                )
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)
    if (showProfileDialog) {
        LocalMcpProfileModal(
            initial = editingProfile,
            presets = settings.networkCidrPresets,
            onPresetsChange = { presets -> vm.updateSettings { current ->
            current.copy(networkCidrPresets = presets)
        } },
            onConfirm = { p ->
                vm.updateSettings { current ->
                    val profiles = current.localMcpProfiles
                    val updated = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p
                    current.copy(localMcpProfiles = updated, activeLocalMcpProfileId = current.activeLocalMcpProfileId ?: p.id)
                }
                showProfileDialog = false
            },
            onDismiss = { showProfileDialog = false },
        )
    }
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                vm.updateSettings { current ->
                    val existingIds = current.mcpServers.map { it.commonOptions.name }.toSet()
                    val toAdd = newConfigs.filter { it.commonOptions.name.isNotBlank() && it.commonOptions.name !in existingIds }
                    current.copy(mcpServers = current.mcpServers + toAdd)
                }
                showImportDialog = false
            },
        )
    }
}

@Composable
private fun LocalMcpServerSection(
    profiles: List<LocalMcpProfile>,
    activeId: String?,
    enabled: Boolean,
    onEnable: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (LocalMcpProfile) -> Unit,
    onDelete: (LocalMcpProfile) -> Unit,
    onSetActive: (LocalMcpProfile) -> Unit,
) {
    var profileToDelete by remember { mutableStateOf<LocalMcpProfile?>(null) }
    CardGroup {
        switchItem(
            checked = enabled,
            onCheckedChange = onEnable,
            headlineContent = { Text(stringResource(R.string.mcp_page_local_server_title)) },
            supportingContent = { Text(stringResource(R.string.mcp_page_local_server_desc)) },
        )
        profiles.forEach { p ->
            val displayName = p.name.ifBlank { stringResource(R.string.mcp_page_unnamed) }
            item(
                headlineContent = {
                    Text(
                        if (p.id == activeId) {
                            stringResource(R.string.mcp_page_current_prefix, displayName)
                        } else {
                            displayName
                        },
                    )
                },
                supportingContent = {
                    Text(stringResource(R.string.mcp_page_port_tools, p.port, p.allowedTools.size))
                },
                trailingContent = {
                    Row {
                        if (p.id != activeId) {
                            TextButton(onClick = { onSetActive(p) }) { Text(stringResource(R.string.mcp_page_set_active)) }
                        }
                        TextButton(onClick = { onEdit(p) }) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = { profileToDelete = p }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        }
        item(
            onClick = onAdd,
            headlineContent = { Text(stringResource(R.string.mcp_page_add_config)) },
        )
    }

    profileToDelete?.let { profile ->
        RikkaDeleteConfirmDialog(
            show = true,
            title = stringResource(R.string.delete),
            message = stringResource(
                R.string.mcp_delete_config_confirm,
                profile.name.ifBlank { stringResource(R.string.mcp_page_unnamed) },
            ),
            onConfirm = {
                profileToDelete = null
                onDelete(profile)
            },
            onDismiss = { profileToDelete = null },
        )
    }
}


@Composable
private fun McpServerItem(
    item: McpServerConfig,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    val dismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    var errorDetail by remember { mutableStateOf<McpStatus.Error?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    errorDetail?.let { error ->
        val context = LocalContext.current
        val fullText = error.detail ?: error.message
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text(item.commonOptions.name.ifBlank { "MCP" }) },
            text = {
                SelectionContainer {
                    Text(
                        text = fullText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.writeClipboardText(fullText)
                        errorDetail = null
                    },
                ) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDetail = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    SwipeToDismissBox(
        state = dismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch { dismissBoxState.reset() }
                    },
                ) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.cancel))
                }
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            dismissBoxState.reset()
                            showDeleteConfirm = true
                        }
                    },
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = modifier,
    ) {
        Surface(
            shape = cardGroupItemShape(0, 1),
            color = CustomColors.listItemColors.containerColor,
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    McpStatus.Idle -> {
                        Icon(HugeIcons.MessageBlocked, null)
                    }

                    McpStatus.Connecting -> {
                        CircularProgressIndicator(
                            modifier =
                                Modifier.size(
                                    24.dp,
                                ),
                        )
                    }

                    McpStatus.Connected -> {
                        Icon(HugeIcons.McpServer, null)
                    }

                    is McpStatus.Reconnecting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    is McpStatus.Error -> {
                        Icon(HugeIcons.AlertCircle, null)
                    }

                    McpStatus.NeedsAuthorization -> {
                        Icon(HugeIcons.AlertCircle, null)
                    }

                    McpStatus.Authorizing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item.commonOptions.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        val dotColor =
                            if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .drawWithContent {
                                        drawCircle(
                                            color = dotColor,
                                        )
                                    },
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            when (item) {
                                is McpServerConfig.SseTransportServer -> Text("SSE")
                                is McpServerConfig.StreamableHTTPServer -> Text(stringResource(R.string.mcp_transport_streamable_http))
                            }
                        }
                    }
                    if (status is McpStatus.Error) {
                        val error = status as McpStatus.Error
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { errorDetail = error },
                        )
                    }
                    if (status == McpStatus.NeedsAuthorization) {
                        val context = LocalContext.current
                        Text(
                            text = stringResource(R.string.mcp_page_oauth_required),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = { mcpManager.startAuthorization(item, context) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.mcp_oauth_authorize))
                        }
                    }
                    if (status == McpStatus.Authorizing) {
                        Text(
                            text = stringResource(R.string.mcp_page_oauth_in_progress),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(
                            onClick = { mcpManager.cancelAuthorization(item) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.mcp_oauth_cancel))
                        }
                    }
                }

                IconButton(
                    onClick = {
                        onEdit(item)
                    },
                ) {
                    Icon(HugeIcons.Settings03, null)
                }
            }
        }
    }

    RikkaDeleteConfirmDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.delete),
        message = stringResource(
            R.string.mcp_delete_server_confirm,
            item.commonOptions.name.ifBlank { stringResource(R.string.mcp_page_unnamed) },
        ),
        onConfirm = {
            showDeleteConfirm = false
            onDelete()
        },
        onDismiss = { showDeleteConfirm = false },
    )
}


internal fun isValidMcpName(name: String): Boolean =
    name.isEmpty() ||
        name.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
        }
