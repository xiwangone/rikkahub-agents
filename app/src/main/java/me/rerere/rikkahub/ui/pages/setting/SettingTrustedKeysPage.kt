package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.JSch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.workspace.ssh.SshHostKey
import java.io.File

/** 一条已信任的主机密钥：来源文件 + known_hosts 行里的主机、算法与密钥 blob。 */
private data class TrustedKeyEntry(
    val host: String,
    val type: String,
    val key: String,
) {
    /** OpenSSH 风格的 SHA256 指纹。 */
    val fingerprint: String
        get() = SshHostKey(type, key).fingerprint
}

/**
 * 受信主机密钥管理页：列出应用与 Vault 两份 known_hosts 里的条目，每行可删除。
 * 删除后下次连接该主机时会再次要求确认指纹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingTrustedKeysPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val appKnownHostsFile = remember(context) { knownHostsFile(context) }
    val vaultKnownHostsFile = remember(context) { File(context.filesDir, "vault_known_hosts") }

    var appEntries by remember { mutableStateOf<List<TrustedKeyEntry>>(emptyList()) }
    var vaultEntries by remember { mutableStateOf<List<TrustedKeyEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            val (app, vault) = withContext(Dispatchers.IO) {
                loadTrustedKeys(appKnownHostsFile) to loadTrustedKeys(vaultKnownHostsFile)
            }
            appEntries = app
            vaultEntries = vault
            loaded = true
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_trusted_keys_title)) },
                navigationIcon = { BackButton() },
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
            item("trustedKeysApp") {
                key(appEntries) {
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_trusted_keys_app)) },
                    ) {
                        if (appEntries.isEmpty() && loaded) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_trusted_keys_empty)) },
                            )
                        }
                        appEntries.forEach { entry ->
                            trustedKeyItem(
                                entry = entry,
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            deleteTrustedKey(appKnownHostsFile, entry)
                                        }
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item("trustedKeysVault") {
                key(vaultEntries) {
                    CardGroup(
                        title = { Text(stringResource(R.string.setting_trusted_keys_vault)) },
                    ) {
                        if (vaultEntries.isEmpty() && loaded) {
                            item(
                                headlineContent = { Text(stringResource(R.string.setting_trusted_keys_empty)) },
                            )
                        }
                        vaultEntries.forEach { entry ->
                            trustedKeyItem(
                                entry = entry,
                                onDelete = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            deleteTrustedKey(vaultKnownHostsFile, entry)
                                        }
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item("trustedKeysHint") {
                Text(
                    text = stringResource(R.string.setting_trusted_keys_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

private fun CardGroupScope.trustedKeyItem(
    entry: TrustedKeyEntry,
    onDelete: () -> Unit,
) {
    item(
        leadingContent = { CardGroupIcon(HugeIcons.Key01) },
        headlineContent = {
            Text(
                text = entry.host,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = "${entry.type} · ${entry.fingerprint}",
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.setting_ssh_host_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/**
 * 用 JSch 的 HostKeyRepository 解析一份 known_hosts 文件。
 * 文件不存在（Vault 那份首次连接前就没有）时按空列表处理。
 */
private fun loadTrustedKeys(file: File): List<TrustedKeyEntry> {
    if (!file.exists()) return emptyList()
    val jsch = JSch()
    runCatching { jsch.setKnownHosts(file.absolutePath) }.getOrNull() ?: return emptyList()
    return jsch.hostKeyRepository.hostKey.orEmpty().map { hostKey ->
        TrustedKeyEntry(host = hostKey.host, type = hostKey.type, key = hostKey.key)
    }
}

/**
 * 从 known_hosts 文件里删掉一条指定条目：过滤重写 + 原子替换，复用 known_hosts
 * 删除的既有模式；[file] 参数化后同一份逻辑覆盖 Vault 那份文件。
 * 两份文件各自独立处理，Vault 的 key 只在 vault 文件里删。
 *
 * @return 是否确实删掉了一条。
 */
private fun deleteTrustedKey(file: File, entry: TrustedKeyEntry): Boolean {
    if (!file.exists()) return false
    val parent = file.parentFile ?: return false
    if (!parent.exists() && !parent.mkdirs()) return false
    val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return false
    var changed = false
    val kept = lines.mapNotNull { line ->
        val rewritten = removeKnownHostsEntry(line, entry)
        if (rewritten != line) changed = true
        rewritten
    }
    if (!changed) return false
    val temp = File(parent, "${file.name}.tmp-${System.nanoTime()}")
    try {
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            kept.forEach { writer.appendLine(it) }
        }
        replaceFilePreservingTarget(temp, file)
    } finally {
        if (temp.exists()) runCatching { temp.delete() }
    }
    return true
}

/**
 * 从一行 known_hosts 文本里去掉 [entry] 匹配的主机。
 * 行格式（known_hosts(5)）：[@标记 …] 主机列表（逗号分隔） 算法 Base64 密钥 [注释]。
 *
 * @return null 表示整行删掉；非 null 表示保留（多主机行只去掉命中的主机后重写）。
 */
private fun removeKnownHostsEntry(line: String, entry: TrustedKeyEntry): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return line
    val tokens = trimmed.split(Regex("\\s+"))
    var i = 0
    val markers = mutableListOf<String>()
    while (i < tokens.size && tokens[i].startsWith("@")) {
        markers += tokens[i]
        i++
    }
    if (tokens.size - i < 3) return line
    val hosts = tokens[i].split(",")
    val type = tokens[i + 1]
    val key = tokens[i + 2]
    if (type != entry.type || key != entry.key) return line
    val entryHosts = entry.host.split(",").map { it.trim() }.toSet()
    val remaining = hosts.filter { it !in entryHosts }
    if (remaining.size == hosts.size) return line
    if (remaining.isEmpty()) return null
    val prefix = if (markers.isEmpty()) "" else markers.joinToString(" ") + " "
    return prefix + remaining.joinToString(",") + " " + tokens.subList(i + 1, tokens.size).joinToString(" ")
}
