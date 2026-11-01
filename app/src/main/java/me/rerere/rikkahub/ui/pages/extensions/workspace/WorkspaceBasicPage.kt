package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.MirrorProbe
import me.rerere.workspace.MirrorSpeedResult
import me.rerere.workspace.measureMirrorSpeeds
import me.rerere.workspace.resolveMirrorProbeUrl
import me.rerere.workspace.WorkspaceMirrorPresets
import me.rerere.workspace.WorkspaceMirrors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bash
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStats


@Composable
internal fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    distro: WorkspaceDistroInfo?,
    stats: WorkspaceStats?,
    installProgress: RootfsInstallProgress?,
    onEditSsh: () -> Unit,
    mirrors: WorkspaceMirrors,
    onInstallRootfs: () -> Unit,
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
    // 远程工作区没有本机 Rootfs：镜像源、安装 Shell、兼容模式、chroot 这些都不适用
    val remote = workspace?.isSsh() == true
    val remoteSettings = if (remote) workspace?.sshSettings() else null
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText =
        when {
            installing -> stringResource(R.string.workspace_detail_installing)
            rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
            else -> stringResource(R.string.workspace_detail_install_rootfs)
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WorkspaceInfoRow(
                        stringResource(R.string.workspace_detail_name),
                        workspace?.name ?: stringResource(R.string.workspace_detail_loading),
                    )
                    distro?.let { info ->
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_detail_distro),
                            info.prettyName,
                        )
                    }
                    if (remote) {
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_ssh_host),
                            remoteSettings?.let { "${it.username}@${it.host}:${it.port}" } ?: "-",
                        )
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_ssh_remote_dir),
                            remoteSettings?.remoteDir ?: workspace?.workspaceDir() ?: "-",
                        )
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_ssh_host_key_column),
                            remoteSettings?.hostKey?.fingerprint ?: stringResource(R.string.workspace_ssh_host_key_missing),
                        )
                    } else {
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_detail_stats_disk),
                            stats?.rootBytes?.fileSizeToString() ?: "-",
                        )
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_detail_stats_packages),
                            stats?.packageCount?.toString() ?: "-",
                        )
                        WorkspaceInfoRow(
                            stringResource(R.string.workspace_detail_stats_kernel),
                            stats?.kernel ?: "-",
                        )
                    }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { tagEditing = true }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_detail_tags),
                            modifier = Modifier.weight(0.35f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.weight(0.65f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val tags = workspace?.workspaceTags().orEmpty()
                            if (tags.isEmpty()) {
                                Text("-", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                tags.fastForEach { tag ->
                                    Surface(shape = CircleShape, tonalElevation = 1.dp) {
                                        Text(
                                            text = tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (remote) {
                        OutlinedButton(onClick = onEditSsh, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.workspace_ssh_edit_connection))
                        }
                    }
                }
            }
        }

        // 以下四张卡片只作用于本机 Rootfs，远程工作区不显示
        if (!remote) {
            item {
                CardGroup(
                    title = { Text(stringResource(R.string.workspace_detail_mirrors)) },
                ) {
                    MirrorRow(
                        title = stringResource(R.string.workspace_detail_mirror_apk),
                        current = mirrors.apk,
                        presets = WorkspaceMirrorPresets.APK,
                        onClick = { mirrorPicker = MirrorPick.APK },
                    )
                    MirrorRow(
                        title = stringResource(R.string.workspace_detail_mirror_apt),
                        current = mirrors.apt,
                        presets = WorkspaceMirrorPresets.APT,
                        onClick = { mirrorPicker = MirrorPick.APT },
                    )
                    MirrorRow(
                        title = stringResource(R.string.workspace_detail_mirror_pip),
                        current = mirrors.pip,
                        presets = WorkspaceMirrorPresets.PIP,
                        onClick = { mirrorPicker = MirrorPick.PIP },
                    )
                    MirrorRow(
                        title = stringResource(R.string.workspace_detail_mirror_npm),
                        current = mirrors.npm,
                        presets = WorkspaceMirrorPresets.NPM,
                        onClick = { mirrorPicker = MirrorPick.NPM },
                    )
                    item(
                        onClick = onRepairCaCerts,
                        headlineContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts)) },
                        supportingContent = { Text(stringResource(R.string.workspace_detail_repair_cacerts_desc)) },
                    )
                }
            }

            item {
                CardGroup(
                    title = { Text(stringResource(R.string.workspace_detail_enable_shell)) },
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ShellStatusPanel(
                            status = shellStatus,
                            isInstalling = installing,
                        )
                        Text(
                            text = stringResource(R.string.workspace_detail_enable_shell_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onInstallRootfs,
                            enabled = workspace != null && !installing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(HugeIcons.Bash, contentDescription = null)
                            Text(
                                text = installButtonText,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.workspace_detail_sdcard_access),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.workspace_detail_sdcard_access_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = sdcardEnabled,
                                onCheckedChange = onSdcardAccessChange,
                            )
                        }
                        installProgress?.let { progress ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Box(modifier = Modifier.padding(12.dp)) {
                                    RootfsProgress(progress)
                                }
                            }
                        }
                    }
                }
            }

            item {
                CardGroup(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.workspace_detail_compatibility_mode))
                            Text(
                                text = stringResource(R.string.workspace_detail_compatibility_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                        trailingContent = {
                            Switch(
                                checked = workspace?.shellCompatibilityMode ?: false,
                                onCheckedChange = onShellCompatibilityModeChange,
                                enabled = workspace != null,
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.workspace_detail_chroot))
                            Text(
                                text = stringResource(R.string.workspace_detail_chroot_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.workspace_detail_chroot)) },
                        trailingContent = {
                            Switch(
                                checked = chrootEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled) showChrootConfirm = true else onChrootEnabledChange(false)
                                },
                            )
                        },
                    )
                    // 开关开着但后端未就绪（脚本缺失）= 实际在跑 proot：显性提示，不静默
                    if (chrootEnabled && !chrootBackendReady) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.workspace_detail_chroot_fallback),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
                if (showChrootConfirm) {
                    AlertDialog(
                        onDismissRequest = { showChrootConfirm = false },
                        title = { Text(stringResource(R.string.workspace_detail_chroot)) },
                        text = { Text(stringResource(R.string.workspace_detail_chroot_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showChrootConfirm = false
                                onChrootEnabledChange(true)
                            }) { Text(stringResource(android.R.string.ok)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showChrootConfirm = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceToolApprovalItems().forEach { (toolName, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = toolName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = resolveWorkspaceToolApproval(toolName, overrides),
                        onCheckedChange = { onToolApprovalChange(toolName, it) },
                        enabled = workspace != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() =
    listOf(
        "workspace_list" to stringResource(R.string.workspace_detail_tool_list_files),
        "workspace_read_folder" to stringResource(R.string.workspace_detail_tool_read_folder),
        "workspace_search_code" to stringResource(R.string.workspace_detail_tool_search_code),
        "workspace_read_file" to stringResource(R.string.workspace_detail_tool_read_file),
        "workspace_write_file" to stringResource(R.string.workspace_detail_tool_write_file),
        "workspace_create_folder" to stringResource(R.string.workspace_detail_tool_create_folder),
        "workspace_edit_file" to stringResource(R.string.workspace_detail_tool_edit_file),
        "workspace_shell" to stringResource(R.string.workspace_detail_tool_shell),
        "workspace_run_background" to stringResource(R.string.workspace_detail_tool_run_background),
        "workspace_background_status" to stringResource(R.string.workspace_detail_tool_background_status),
        "workspace_background_kill" to stringResource(R.string.workspace_detail_tool_background_kill),
    )

@Composable
private fun ShellStatusPanel(
    status: String?,
    isInstalling: Boolean,
) {
    val isReady = status == WorkspaceShellStatus.READY.name
    val isBroken = status == WorkspaceShellStatus.BROKEN.name
    val colorScheme = MaterialTheme.colorScheme
    val containerColor =
        when {
            isInstalling -> colorScheme.tertiaryContainer
            isReady -> colorScheme.primaryContainer
            isBroken -> colorScheme.errorContainer
            else -> colorScheme.surfaceContainerHigh
        }
    val contentColor =
        when {
            isInstalling -> colorScheme.onTertiaryContainer
            isReady -> colorScheme.onPrimaryContainer
            isBroken -> colorScheme.onErrorContainer
            else -> colorScheme.onSurfaceVariant
        }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(contentColor, CircleShape),
            )
            Text(
                text = status?.toShellStatusLabel() ?: stringResource(R.string.workspace_detail_loading),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
        }
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
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

