package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.MirrorProbe
import me.rerere.workspace.MirrorSpeedResult
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceMirrorPresets
import me.rerere.workspace.WorkspaceMirrors
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStats
import me.rerere.workspace.measureMirrorSpeeds
import me.rerere.workspace.resolveMirrorProbeUrl

@Composable
internal fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    distro: WorkspaceDistroInfo?,
    stats: WorkspaceStats?,
    installProgress: RootfsInstallProgress?,
    onEditSsh: () -> Unit,
    mirrors: WorkspaceMirrors,
    onInstallRootfs: () -> Unit,
    onUpdateRootfs: () -> Unit = {},
    upgradingPackages: Boolean = false,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onApplyMirrors: (WorkspaceMirrors) -> Unit,
    onRepairCaCerts: () -> Unit,
    sdcardEnabled: Boolean,
    onSdcardAccessChange: (Boolean) -> Unit,
    chrootEnabled: Boolean,
    chrootBackendReady: Boolean,
    onChrootEnabledChange: (Boolean) -> Unit,
    onSetTags: (List<String>) -> Unit,
) {
    var mirrorPicker by remember { mutableStateOf<MirrorPick?>(null) }
    val scope = rememberCoroutineScope()
    val speedResults = remember { mutableStateMapOf<MirrorPick, Map<String, MirrorSpeedResult>>() }
    var speedTestingPick by remember { mutableStateOf<MirrorPick?>(null) }
    var tagEditing by remember { mutableStateOf(false) }
    var showChrootConfirm by remember { mutableStateOf(false) }
    val shellStatus = workspace?.shellStatus
    val remote = workspace?.isSsh() == true
    val remoteSettings = if (remote) workspace?.sshSettings() else null
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name || upgradingPackages
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText =
        when {
            upgradingPackages -> stringResource(R.string.workspace_detail_upgrading_packages)
            installing -> stringResource(R.string.workspace_detail_installing)
            rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
            else -> stringResource(R.string.workspace_detail_install_rootfs)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                item(
                    leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                    headlineContent = { Text(stringResource(R.string.workspace_detail_name)) },
                    supportingContent = {
                        Text(workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    },
                    trailingContent =
                        if (!remote) {
                            { ShellStatusBadge(status = shellStatus, isInstalling = installing) }
                        } else {
                            null
                        },
                )
                distro?.let { info ->
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Cpu) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_distro)) },
                        supportingContent = { Text(info.prettyName) },
                    )
                }
                if (remote) {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.ServerStack01) },
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_host)) },
                        supportingContent = {
                            Text(remoteSettings?.let { "${it.username}@${it.host}:${it.port}" } ?: "-")
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Folder01) },
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_remote_dir)) },
                        supportingContent = {
                            Text(remoteSettings?.remoteDir ?: workspace?.workspaceDir() ?: "-")
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Key01) },
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_host_key_column)) },
                        supportingContent = {
                            val hostKey = remoteSettings?.hostKey
                            Text(
                                text = hostKey?.fingerprint ?: stringResource(R.string.workspace_ssh_host_key_missing),
                                fontFamily = if (hostKey != null) FontFamily.Monospace else null,
                            )
                        },
                    )
                    item(
                        onClick = onEditSsh,
                        leadingContent = { CardGroupIcon(HugeIcons.PencilEdit01) },
                        headlineContent = { Text(stringResource(R.string.workspace_ssh_edit_connection)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                } else {
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Database02) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_stats_disk)) },
                        trailingContent = {
                            CardGroupValue(stats?.rootBytes?.fileSizeToString() ?: "-")
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Package01) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_stats_packages)) },
                        trailingContent = {
                            CardGroupValue(stats?.packageCount?.toString() ?: "-")
                        },
                    )
                    item(
                        leadingContent = { CardGroupIcon(HugeIcons.Settings03) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_stats_kernel)) },
                        supportingContent = {
                            Text(
                                text = stats?.kernel ?: "-",
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                item(
                    onClick = { tagEditing = true },
                    leadingContent = { CardGroupIcon(HugeIcons.Pin) },
                    headlineContent = { Text(stringResource(R.string.workspace_detail_tags)) },
                    supportingContent = {
                        val tags = workspace?.workspaceTags().orEmpty()
                        if (tags.isEmpty()) {
                            Text("-")
                        } else {
                            FlowRow(
                                modifier = Modifier.padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                tags.fastForEach { tag ->
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    ) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    trailingContent = {
                        Icon(
                            imageVector = HugeIcons.Edit02,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        if (!remote) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.workspace_detail_enable_shell)) },
                ) {
                    formItem(
                        leadingContent = { CardGroupIcon(HugeIcons.Bash) },
                        headlineContent = {
                            Text(distro?.prettyName ?: stringResource(R.string.workspace_detail_area_rootfs))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.workspace_detail_enable_shell_desc))
                        },
                        trailingContent = {
                            ShellStatusBadge(status = shellStatus, isInstalling = installing)
                        },
                    ) {
                        if (rootfsReady && !installing) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = onUpdateRootfs,
                                    enabled = workspace != null,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Refresh01,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(
                                        text = stringResource(R.string.workspace_detail_update_distro),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(
                                    onClick = onInstallRootfs,
                                    enabled = workspace != null,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.Bash,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                    Text(
                                        text = installButtonText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = onInstallRootfs,
                                enabled = workspace != null && !installing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Bash,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(installButtonText)
                            }
                        }
                        installProgress?.let { progress ->
                            RootfsProgress(progress)
                        }
                    }
                    switchItem(
                        checked = sdcardEnabled,
                        onCheckedChange = onSdcardAccessChange,
                        leadingContent = { CardGroupIcon(HugeIcons.SmartPhone01) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_sdcard_access)) },
                        supportingContent = {
                            Text(stringResource(R.string.workspace_detail_sdcard_access_desc))
                        },
                    )
                    switchItem(
                        checked = workspace?.shellCompatibilityMode ?: false,
                        onCheckedChange = onShellCompatibilityModeChange,
                        enabled = workspace != null,
                        leadingContent = { CardGroupIcon(HugeIcons.Wrench01) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                        supportingContent = {
                            Text(stringResource(R.string.workspace_detail_compatibility_mode_desc))
                        },
                    )
                    switchItem(
                        checked = chrootEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) showChrootConfirm = true else onChrootEnabledChange(false)
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Shield01) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_chroot)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.workspace_detail_chroot_desc))
                                if (chrootEnabled && !chrootBackendReady) {
                                    Text(
                                        text = stringResource(R.string.workspace_detail_chroot_fallback),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
                if (showChrootConfirm) {
                    AlertDialog(
                        onDismissRequest = { showChrootConfirm = false },
                        title = { Text(stringResource(R.string.workspace_detail_chroot)) },
                        text = { Text(stringResource(R.string.workspace_detail_chroot_confirm)) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showChrootConfirm = false
                                    onChrootEnabledChange(true)
                                },
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showChrootConfirm = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.workspace_detail_mirrors)) },
                ) {
                    MirrorRow(
                        icon = HugeIcons.Package01,
                        title = stringResource(R.string.workspace_detail_mirror_apk),
                        current = mirrors.apk,
                        presets = WorkspaceMirrorPresets.APK,
                        onClick = { mirrorPicker = MirrorPick.APK },
                    )
                    MirrorRow(
                        icon = HugeIcons.ServerStack01,
                        title = stringResource(R.string.workspace_detail_mirror_apt),
                        current = mirrors.apt,
                        presets = WorkspaceMirrorPresets.APT,
                        onClick = { mirrorPicker = MirrorPick.APT },
                    )
                    MirrorRow(
                        icon = HugeIcons.Code,
                        title = stringResource(R.string.workspace_detail_mirror_pip),
                        current = mirrors.pip,
                        presets = WorkspaceMirrorPresets.PIP,
                        onClick = { mirrorPicker = MirrorPick.PIP },
                    )
                    MirrorRow(
                        icon = HugeIcons.Earth,
                        title = stringResource(R.string.workspace_detail_mirror_npm),
                        current = mirrors.npm,
                        presets = WorkspaceMirrorPresets.NPM,
                        onClick = { mirrorPicker = MirrorPick.NPM },
                    )
                    item(
                        onClick = onRepairCaCerts,
                        leadingContent = { CardGroupIcon(HugeIcons.Shield01) },
                        headlineContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts)) },
                        supportingContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts_desc)) },
                        trailingContent = {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }

    MirrorPickerDialog(
        pick = mirrorPicker,
        mirrors = mirrors,
        speedResults = mirrorPicker?.let { speedResults[it] }.orEmpty(),
        speedTesting = speedTestingPick != null && speedTestingPick == mirrorPicker,
        onDismiss = { mirrorPicker = null },
        onSpeedTest = onSpeedTest@{
            val pick = mirrorPicker ?: return@onSpeedTest
            if (speedTestingPick != null) return@onSpeedTest
            val probes =
                pick.presets().mapNotNull { preset ->
                    resolveMirrorProbeUrl(preset, distro?.prettyName)?.let { MirrorProbe(preset.id, it) }
                }
            if (probes.isEmpty()) return@onSpeedTest
            speedResults.remove(pick)
            speedTestingPick = pick
            scope.launch {
                val results =
                    withContext(Dispatchers.IO) {
                        measureMirrorSpeeds(probes)
                    }
                speedResults[pick] = results.associate { it.id to it }
                speedTestingPick = null
            }
        },
        onSelect = { url ->
            onApplyMirrors(
                when (mirrorPicker) {
                    MirrorPick.APK -> mirrors.copy(apk = url)
                    MirrorPick.APT -> mirrors.copy(apt = url)
                    MirrorPick.PIP -> mirrors.copy(pip = url)
                    MirrorPick.NPM -> mirrors.copy(npm = url)
                    null -> mirrors
                },
            )
            mirrorPicker = null
        },
    )

    if (tagEditing) {
        TagEditDialog(
            initial = workspace?.workspaceTags().orEmpty(),
            onDismiss = { tagEditing = false },
            onSave = { tags ->
                onSetTags(tags)
                tagEditing = false
            },
        )
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()

    CardGroup(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.workspace_detail_tool_approval))
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        workspaceToolApprovalItems().forEach { (toolName, label, icon) ->
            switchItem(
                checked = resolveWorkspaceToolApproval(toolName, overrides),
                onCheckedChange = { onToolApprovalChange(toolName, it) },
                enabled = workspace != null,
                leadingContent = { CardGroupIcon(icon) },
                headlineContent = { Text(label) },
                supportingContent = {
                    Text(
                        text = toolName,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun workspaceToolApprovalItems(): List<Triple<String, String, ImageVector>> =
    listOf(
        Triple("workspace_list", stringResource(R.string.workspace_detail_tool_list_files), HugeIcons.LeftToRightListBullet),
        Triple("workspace_read_folder", stringResource(R.string.workspace_detail_tool_read_folder), HugeIcons.Folder01),
        Triple("workspace_search_code", stringResource(R.string.workspace_detail_tool_search_code), HugeIcons.Search01),
        Triple("workspace_read_file", stringResource(R.string.workspace_detail_tool_read_file), HugeIcons.FileView),
        Triple("workspace_write_file", stringResource(R.string.workspace_detail_tool_write_file), HugeIcons.PencilEdit01),
        Triple("workspace_create_folder", stringResource(R.string.workspace_detail_tool_create_folder), HugeIcons.FolderAdd),
        Triple("workspace_edit_file", stringResource(R.string.workspace_detail_tool_edit_file), HugeIcons.FileEdit),
        Triple("workspace_shell", stringResource(R.string.workspace_detail_tool_shell), HugeIcons.ComputerTerminal01),
        Triple("workspace_run_background", stringResource(R.string.workspace_detail_tool_run_background), HugeIcons.Play),
        Triple("workspace_background_status", stringResource(R.string.workspace_detail_tool_background_status), HugeIcons.Clock02),
        Triple("workspace_background_kill", stringResource(R.string.workspace_detail_tool_background_kill), HugeIcons.StopCircle),
    )

@Composable
private fun ShellStatusBadge(
    status: String?,
    isInstalling: Boolean,
) {
    val isReady = status == WorkspaceShellStatus.READY.name
    val isBroken = status == WorkspaceShellStatus.BROKEN.name
    val colorScheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) =
        when {
            isInstalling -> colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer
            isReady -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
            isBroken -> colorScheme.errorContainer to colorScheme.onErrorContainer
            else -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
        }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = status?.toShellStatusLabel() ?: stringResource(R.string.workspace_detail_loading),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction =
            progress.totalBytes?.takeIf { it > 0 }?.let {
                (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
            }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text =
                when (progress.stage) {
                    RootfsInstallStage.DOWNLOADING -> {
                        val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                        stringResource(
                            R.string.workspace_detail_downloading,
                            progress.bytesRead.fileSizeToString(),
                            total,
                        )
                    }

                    RootfsInstallStage.EXTRACTING -> {
                        val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                        stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                    }

                    RootfsInstallStage.INSTALLED -> {
                        stringResource(R.string.workspace_detail_install_complete)
                    }
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
