package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.rikkahub.data.log.AppLog

import me.rerere.workspace.WorkspaceMirrorPresets
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.workspace.WorkspaceMirrorPreset
import androidx.activity.compose.rememberLauncherForActivityResult
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File



@Composable
internal fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onConfirmFile: (String) -> Unit,
) {
    var url by rememberSaveable(workspace.id) { mutableStateOf(DEFAULT_ROOTFS_URL) }
    val context = LocalContext.current
    // Debian 预置地址含构建日期，需在线解析最新目录后再填入（见 resolveLxcDebianRootfs）
    val scope = rememberCoroutineScope()
    // 本地导入：先把所选归档复制到应用缓存，再交给安装流程（安装线程无法直接读 SAF 流）
    val pickArchiveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: android.net.Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "rootfs.tar.gz"
            val suffix =
                if (name.endsWith(".tar.xz", true) || name.endsWith(".txz", true)) ".tar.xz" else ".tar.gz"
            val target = File(context.cacheDir, "rootfs-import-${System.currentTimeMillis()}$suffix")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to read selected file")
            }.onSuccess {
                onConfirmFile(target.absolutePath)
            }
        }

    var savedUrls by remember { mutableStateOf(loadSavedRootfsUrls(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_detail_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                    maxLines = 5,
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
                        AssistChip(
                            onClick = {
                                if (preset.lxcDebian) {
                                    // 地址含构建日期：点击时解析最新目录，失败回落到写死地址
                                    scope.launch { url = resolveLxcDebianRootfs() ?: preset.url }
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
                                Text(text = saved, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(
                                onClick = {
                                    val updated = savedUrls - saved
                                    savedUrls = updated
                                    saveRootfsUrls(context, updated)
                                },
                            ) {
                                Text(stringResource(R.string.workspace_detail_delete_saved_url))
                            }
                        }
                    }
                }
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_detail_save_rootfs_url))
                }
                OutlinedButton(
                    onClick = { pickArchiveLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.workspace_detail_import_local_rootfs))
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
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_install))
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

private const val DEFAULT_ROOTFS_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"

@Composable
internal fun WorkspaceInfoRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.35f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.65f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    /** true 表示该条来自 LXC 镜像站：地址含构建日期，点击时尝试解析最新目录，失败用 [url] 兜底。 */
    val lxcDebian: Boolean = false,
)

/** 运行设备是否为 arm64 —— 预置 rootfs 需按设备 ABI 选地址（rootfs 必须匹配设备 ABI）。 */
private val isArm64Device: Boolean
    get() =
        System.getProperty("os.arch").orEmpty().lowercase().let { it == "aarch64" || it == "arm64" }

/** 按设备 ABI 在 arm64 / amd64 两个下载地址间二选一。 */
private fun presetRootfs(label: String, arm64: String, amd64: String, lxcDebian: Boolean = false) =
    PresetRootfsUrl(label, if (isArm64Device) arm64 else amd64, lxcDebian)

/** LXC 镜像站使用的架构目录名。 */
private val lxcAbiDir: String get() = if (isArm64Device) "arm64" else "amd64"

/**
 * 解析 LXC 镜像站 Debian 的最新构建目录，返回 rootfs 地址；全部失败返回 null（调用方兜底）。
 *
 * 先试镜像站（只同步最新一天，取任一即可），再试主源（列多天，取最大日期）。
 * 地址形如 `<base>/trixie/<abi>/default/<yyyyMMdd_HH:mm>/rootfs.tar.xz`。
 */
private suspend fun resolveLxcDebianRootfs(): String? =
    withContext(Dispatchers.IO) {
        val bases =
            listOf(
                // 核实：清华镜像虽有 lxc-images，但该分支下 rootfs.tar.xz 返回 404（同步不全）；
                // 南大镜像目录完整且 rootfs.tar.xz 为 200，主源 302→200 作兜底。
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

/**
 * 预置 rootfs 源：均已在移动网络下核验可达（GitHub 系地址不可达，勿加入）。
 *
 * 地址按设备 ABI 选择。⚠ Debian 取自 LXC 镜像站，路径含构建日期：点击时会在线解析最新目录，
 * 此处只是解析失败时的离线兜底，日期滚动后如兜底失效需更新。
 */
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
                "https://mirror.nju.edu.cn/lxc-images/images/debian/trixie/arm64/default/20261001_05:24/rootfs.tar.xz",
                "https://mirror.nju.edu.cn/lxc-images/images/debian/trixie/amd64/default/20261001_05:24/rootfs.tar.xz",
                lxcDebian = true,
            ),
            presetRootfs(
                "Alpine 3.24.2 minirootfs",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.2-x86_64.tar.gz",
            ),
        )


/** Which package-manager mirror is being picked. */
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
        headlineContent = { Text(title) },
        supportingContent = { Text(label) },
    )
}

