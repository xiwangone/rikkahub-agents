package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import me.rerere.rikkahub.ui.components.ui.ScrollableDialogContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Package01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.workspace.WorkspaceDistroInfo
import me.rerere.workspace.WorkspaceMirrorPreset
import me.rerere.workspace.WorkspaceMirrorPresets
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File

@Composable
internal fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    isUpdate: Boolean = false,
    distro: WorkspaceDistroInfo? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmFile: (String) -> Unit,
    onUpgradePackages: (() -> Unit)? = null,
) {
    val initialUrl = remember(workspace.id, isUpdate, distro?.name, distro?.version) {
        defaultRootfsUrlForDistro(distro)
    }
    var url by rememberSaveable(workspace.id, isUpdate) { mutableStateOf(initialUrl) }
    var resolvingLxc by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(isUpdate, distro?.name) {
        if (url.contains("/images/debian/trixie/")) {
            resolvingLxc = true
            resolveLxcDebianRootfs()?.let { url = it }
            resolvingLxc = false
        }
    }

    val pickArchiveLauncher =
        rememberSafeLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: android.net.Uri? ->
            if (uri == null) return@rememberSafeLauncherForActivityResult
            scope.launch {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "rootfs.tar.gz"
                val suffix =
                    if (name.endsWith(".tar.xz", true) || name.endsWith(".txz", true)) ".tar.xz" else ".tar.gz"
                val target = File(context.cacheDir, "rootfs-import-${System.currentTimeMillis()}$suffix")
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Unable to read selected file")
                    }
                }.onSuccess {
                    onConfirmFile(target.absolutePath)
                }
            }
        }

    var savedUrls by remember { mutableStateOf(loadSavedRootfsUrls(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isUpdate) R.string.workspace_detail_update_distro else R.string.workspace_detail_install_rootfs,
                ),
            )
        },
        text = {
            ScrollableDialogContent {
                Text(
                    text =
                        stringResource(
                            if (isUpdate) {
                                R.string.workspace_detail_update_distro_desc
                            } else {
                                R.string.workspace_detail_install_rootfs_desc
                            },
                            workspace.name,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isUpdate && onUpgradePackages != null) {
                    OutlinedButton(
                        onClick = onUpgradePackages,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Package01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.workspace_detail_upgrade_packages),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 4,
                )
                Text(
                    text = stringResource(R.string.workspace_detail_preset_rootfs_urls),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PRESET_ROOTFS_URLS.forEach { preset ->
                        val selected =
                            url == preset.url ||
                                (preset.lxcDebian && url.contains("/images/debian/trixie/"))
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (preset.lxcDebian) {
                                    url = preset.url
                                    scope.launch {
                                        resolvingLxc = true
                                        url = resolveLxcDebianRootfs() ?: preset.url
                                        resolvingLxc = false
                                    }
                                } else {
                                    url = preset.url
                                }
                            },
                            label = {
                                Text(text = preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
                if (savedUrls.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.workspace_detail_saved_rootfs_urls),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    savedUrls.forEach { saved ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { url = saved },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = saved,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val updated = savedUrls - saved
                                    savedUrls = updated
                                    saveRootfsUrls(context, updated)
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.workspace_detail_delete_saved_url),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val candidate = url.trim()
                            if (candidate.isNotBlank()) {
                                val updated =
                                    (listOf(candidate) + savedUrls).distinct().take(MAX_SAVED_ROOTFS_URLS)
                                savedUrls = updated
                                saveRootfsUrls(context, updated)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FloppyDisk,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.workspace_detail_save_rootfs_url),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { pickArchiveLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.workspace_detail_import_local_rootfs),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.workspace_detail_import_local_rootfs_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.contains("/images/debian/trixie/")) {
                        scope.launch {
                            resolvingLxc = true
                            val resolved = resolveLxcDebianRootfs() ?: trimmed
                            resolvingLxc = false
                            onConfirm(resolved)
                        }
                    } else {
                        onConfirm(trimmed)
                    }
                },
                enabled = url.isNotBlank() && !resolvingLxc,
            ) {
                Text(stringResource(if (isUpdate) R.string.common_update else R.string.common_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun String.toShellStatusLabel(): String =
    when (this) {
        WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
        WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
        WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
        WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
        else -> lowercase()
    }

/** 列表里的一行状态：远程工作区显示连的是哪台机器，本地显示 Rootfs 状态 */
@Composable
internal fun WorkspaceEntity.statusLabel(): String =
    if (isSsh()) {
        sshSettings()?.let { settings ->
            stringResource(R.string.workspace_status_ssh, settings.username, settings.host)
        } ?: stringResource(R.string.workspace_type_ssh)
    } else {
        shellStatus.toShellStatusLabel()
    }

private val DEFAULT_ROOTFS_URL: String
    get() =
        if (isArm64Device) {
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
        } else {
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz"
        }

private fun defaultRootfsUrlForDistro(distro: WorkspaceDistroInfo?): String {
    // WorkspaceDistroInfo 的字段是 name / version（来自 os-release 的 ID / VERSION_ID）
    val id = distro?.name?.lowercase().orEmpty()
    val version = distro?.version.orEmpty()
    val pretty = distro?.prettyName?.lowercase().orEmpty()
    return when {
        id == "alpine" || pretty.contains("alpine") ->
            PRESET_ROOTFS_URLS.firstOrNull { it.label.startsWith("Alpine") }?.url ?: DEFAULT_ROOTFS_URL
        id == "debian" || pretty.contains("debian") ->
            PRESET_ROOTFS_URLS.firstOrNull { it.lxcDebian }?.url ?: DEFAULT_ROOTFS_URL
        (id == "ubuntu" || pretty.contains("ubuntu")) && (version.startsWith("26.") || pretty.contains("26.04")) ->
            PRESET_ROOTFS_URLS.firstOrNull { it.label.startsWith("Ubuntu 26.04") }?.url ?: DEFAULT_ROOTFS_URL
        else -> DEFAULT_ROOTFS_URL
    }
}

private const val MAX_SAVED_ROOTFS_URLS = 5
private const val ROOTFS_URL_HISTORY_KEY = "rootfs_url_history"

private fun loadSavedRootfsUrls(context: android.content.Context): List<String> =
    context
        .readStringPreference(ROOTFS_URL_HISTORY_KEY)
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toList()
        .orEmpty()

private fun saveRootfsUrls(context: android.content.Context, urls: List<String>) {
    context.writeStringPreference(ROOTFS_URL_HISTORY_KEY, urls.joinToString("\n"))
}

private data class PresetRootfsUrl(
    val label: String,
    val url: String,
    val lxcDebian: Boolean = false,
)

private val isArm64Device: Boolean
    get() =
        System.getProperty("os.arch").orEmpty().lowercase().let { it == "aarch64" || it == "arm64" }

private fun presetRootfs(label: String, arm64: String, amd64: String, lxcDebian: Boolean = false) =
    PresetRootfsUrl(label, if (isArm64Device) arm64 else amd64, lxcDebian)

private val lxcAbiDir: String get() = if (isArm64Device) "arm64" else "amd64"

internal suspend fun resolveLxcDebianRootfs(): String? =
    withContext(Dispatchers.IO) {
        val bases =
            listOf(
                "https://mirror.nju.edu.cn/lxc-images/images/debian",
                "https://images.linuxcontainers.org/images/debian",
            )
        val stamp = Regex("""href="(\d{8}_\d{2}(?:%3A|:)\d{2})/"""")
        for (base in bases) {
            val dir = "$base/trixie/$lxcAbiDir/default/"
            val html =
                runCatching {
                    okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .build().newCall(
                            okhttp3.Request.Builder().url(dir)
                                .header("User-Agent", "okhttp")
                                .build(),
                        ).execute().use { resp ->
                            check(resp.isSuccessful) { "mirror probe failed: HTTP ${resp.code}" }
                            resp.body?.string().orEmpty()
                        }
                }
                    .onFailure { AppLog.w("WorkspaceRootfs", "lxc parse failed: $dir ${it.message}") }
                    .getOrNull() ?: continue
            val stamps = stamp.findAll(html).map { it.groupValues[1] }.distinct().sorted()
            val latest = stamps.lastOrNull() ?: continue
            return@withContext "$dir$latest/rootfs.tar.xz"
        }
        null
    }

private val PRESET_ROOTFS_URLS: List<PresetRootfsUrl>
    get() =
        listOf(
            presetRootfs(
                "Ubuntu 26.04.1 base",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-amd64.tar.gz",
            ),
            presetRootfs(
                "Ubuntu 24.04.5 base",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-amd64.tar.gz",
            ),
            presetRootfs(
                "Debian 13 trixie base",
                "https://mirror.nju.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261105_05:24/rootfs.tar.xz",
                "https://mirror.nju.edu.cn/lxc-images/images/debian/trixie/amd64/default/20261105_05:24/rootfs.tar.xz",
                lxcDebian = true,
            ),
            presetRootfs(
                "Alpine 3.24.3 minirootfs",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.3-x86_64.tar.gz",
            ),
        )

internal enum class MirrorPick { APK, APT, PIP, NPM }

internal fun MirrorPick.presets(): List<WorkspaceMirrorPreset> =
    when (this) {
        MirrorPick.APK -> WorkspaceMirrorPresets.APK
        MirrorPick.APT -> WorkspaceMirrorPresets.APT
        MirrorPick.PIP -> WorkspaceMirrorPresets.PIP
        MirrorPick.NPM -> WorkspaceMirrorPresets.NPM
    }

@Composable
internal fun CardGroupScope.MirrorRow(
    icon: ImageVector,
    title: String,
    current: String,
    presets: List<WorkspaceMirrorPreset>,
    onClick: () -> Unit,
) {
    val label =
        presets.firstOrNull { it.url == current }?.label
            ?: current.takeIf { it.isNotBlank() }
            ?: presets.firstOrNull()?.label.orEmpty()
    item(
        onClick = onClick,
        leadingContent = { CardGroupIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
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
