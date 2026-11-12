package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.HostAuthResolution
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatform
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuthDetailed
import me.rerere.rikkahub.data.ai.tools.local.withUtf8ConsoleEncoding
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.extensions.workspace.HostKeyConfirmDialog
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.ssh.SshHostKey
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.util.Base64

private val SSH_QUICK_COMMANDS_POSIX =
    listOf(
        "ls -la",
        "pwd",
        "df -h",
        "free -m",
        "uname -a",
    )

private val SSH_QUICK_COMMANDS_WINDOWS =
    listOf(
        "Get-ComputerInfo",
        "Get-PSDrive",
        "Get-Process",
        "Get-Service",
        "Get-Date",
    )

/**
 * SSH 终端页（Material 3 Expressive 终端面板 + 快捷命令栏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LocalContextGetResourceValueCall")
@Composable
fun SshTerminalPage(hostName: String) {
    val hostRepo: SshHostRepository = koinInject()
    val vaultRepo: CredentialVaultRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val appContext = LocalContext.current
    val appResources = LocalResources.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connectNonce by remember { mutableStateOf(0) }
    // 远端是否 Windows：建连成功后由显式平台（主机设置 > 全局默认）或服务端 banner 算出；
    // null 表示尚未判定，命令发送时回退到命令特征判据。
    var isWindowsRemote by remember { mutableStateOf<Boolean?>(null) }
    var pendingHostKey by remember { mutableStateOf<PendingTerminalHostKey?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val sessionRef = remember { java.util.concurrent.atomic.AtomicReference<com.jcraft.jsch.Session?>(null) }
    // 按行渲染输出：每行独立布局，新行追加时只测量新增行，不再全量重组整段文本。
    val outputLines = remember(output) {
        if (output.isEmpty()) {
            emptyList()
        } else {
            output.split("\n").let { lines ->
                if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
        }
    }
    val outputListState = rememberLazyListState()

    DisposableEffect(hostName) {
        onDispose {
            runCatching { sessionRef.getAndSet(null)?.disconnect() }
        }
    }

    // 只有用户已在底部时才跟随新输出；上翻看历史时不拽回到底部。
    LaunchedEffect(output.length) {
        val lastIndex = outputLines.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        val lastVisible = outputListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        val atBottom = lastVisible == null || lastVisible >= lastIndex - 1
        if (atBottom) {
            outputListState.animateScrollToItem(lastIndex)
        }
    }

    // 建连入口：首次进入时调用；用户在指纹弹窗上点信任后，带上待确认的密钥再调一次，
    // 先把密钥写入 known_hosts 再重连。
    suspend fun connect(trust: PendingTerminalHostKey? = null) {
        withContext(Dispatchers.IO) {
            connectError = null
            connected = false
            val host = hostRepo.getByName(hostName)
            if (host == null) {
                AppLog.w("SshTerm", "connect: host not found $hostName")
                connectError = appContext.getString(R.string.ssh_terminal_host_not_found, hostName)
                return@withContext
            }
            if (trust != null) {
                try {
                    trustHostKey(appContext, host.host, trust.actual)
                } catch (e: IOException) {
                    connectError = appContext.getString(R.string.ssh_terminal_connect_failed, e.message)
                    AppLog.w("SshTerm", "trust host key failed: ${e.message}")
                    return@withContext
                }
            }
            val authResolution = resolveHostAuthDetailed(host, vaultRepo)
            val auth = (authResolution as? HostAuthResolution.Ready)?.auth
            AppLog.w(
                "SshTerm",
                "connect: $hostName → " + ((authResolution as? HostAuthResolution.Unusable)?.reason ?: "credential ok"),
            )
            if (auth == null) {
                connectError = appContext.getString(R.string.ssh_terminal_no_credential, host.vaultCredentialRef ?: "none")
                return@withContext
            }
            var hostKeyRepo: VerifyingHostKeyRepository? = null
            var lastError: JSchException? = null
            for (attempt in 0 until 2) {
                try {
                    val jsch = newJSch(appContext)
                    val repo = VerifyingHostKeyRepository(jsch.hostKeyRepository)
                    hostKeyRepo = repo
                    jsch.hostKeyRepository = repo
                    val (dialHost, dialPort) = me.rerere.workspace.ssh.TunnelRouter.resolve(host.host, host.port)
                    val session = jsch.getSession(host.user, dialHost, dialPort)
                    // 拨号地址可能是隧道分配的本地地址，主机密钥仍按用户填的主机名比对，
                    // 否则同一主机的密钥变更会被当成新主机放过。
                    if (dialHost != host.host) session.setHostKeyAlias(host.host)
                    // 严格校验：只认 known_hosts 里已信任的密钥；未知 / 变更的密钥
                    // 由 VerifyingHostKeyRepository 记下并走指纹确认弹窗，绝不静默信任。
                    session.setConfig("StrictHostKeyChecking", "yes")
                    session.setConfig("ServerAliveInterval", "30")
                    session.setConfig("ServerAliveCountMax", "3")
                    auth.password?.let { session.setPassword(it) }
                    auth.privateKey?.let {
                        jsch.addIdentity("ssh-term-$hostName", it.ensureTrailingNewline().toByteArray(Charsets.UTF_8), null, null)
                    }
                    session.connect(10000)
                    sessionRef.set(session)
                    connected = true
                    // 平台判定：主机显式设置 > 服务端 banner（建连时获取）> 全局默认；决定快捷命令栏用哪套
                    // 命令，以及后续命令是否补 UTF-8 输出前缀。
                    isWindowsRemote = when (SshPlatform.fromPreference(host.platform)) {
                        SshPlatform.WINDOWS -> true
                        SshPlatform.LINUX -> false
                        else -> RemotePlatform.isWindowsServer(session.serverVersion)
                            ?: when (settingsStore.settingsFlow.value.defaultSshPlatform) {
                                SshPlatform.WINDOWS -> true
                                SshPlatform.LINUX -> false
                                else -> null
                            }
                    }
                    connectError = null
                    output = appContext.getString(R.string.ssh_terminal_connected, host.user, host.host, host.port) + "\n"
                    AppLog.i("SshTerm", "connected ${host.user}@${host.host}:${host.port}")
                    lastError = null
                    break
                } catch (e: JSchException) {
                    lastError = e
                    AppLog.w("SshTerm", "connect attempt ${attempt + 1} failed: ${e.message}")
                    // Authentication failures are deterministic; only retry transient network failures once.
                    if (e.message?.contains("Auth fail") == true) break
                    // 未知 / 变更的主机密钥走确认弹窗，重试不会改变结果，直接退出循环。
                    if (hostKeyRepo?.unknownKey != null || hostKeyRepo?.changedKey != null) break
                    if (attempt == 0) {
                        runCatching { Thread.sleep(800) }
                    }
                }
            }
            if (!connected) {
                val unknown = hostKeyRepo?.unknownKey
                val changed = hostKeyRepo?.changedKey
                when {
                    unknown != null -> {
                        pendingHostKey = PendingTerminalHostKey(host.host, "${host.user}@${host.host}:${host.port}", unknown, null)
                        AppLog.w("SshTerm", "connect: unknown host key ${unknown.type} ${unknown.fingerprint}")
                    }
                    changed != null -> {
                        pendingHostKey = PendingTerminalHostKey(host.host, "${host.user}@${host.host}:${host.port}", changed.second, changed.first)
                        AppLog.w("SshTerm", "connect: host key changed for ${host.host}")
                    }
                    lastError != null -> {
                        connectError = appContext.getString(R.string.ssh_terminal_connect_failed, lastError.message)
                        AppLog.w("SshTerm", "connect failed: ${lastError.message}")
                    }
                }
            }
        }
    }

    LaunchedEffect(hostName, connectNonce) {
        connect()
    }

    fun runCmd(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isBlank()) return
        output += "\n$ $trimmed\n"
        scope.launch {
            withContext(Dispatchers.IO) {
                val session = sessionRef.get()
                if (session == null || !session.isConnected) {
                    sessionRef.set(null)
                    connected = false
                    // 断线也要弹出错误卡片（带“重试”按钮），否则输入框和快捷栏
                    // 消失后用户只能退出页面再进来重连。
                    connectError = appContext.getString(R.string.ssh_terminal_connection_lost)
                    output += appContext.getString(R.string.ssh_terminal_connection_lost) + "\n"
                    return@withContext
                }
                // 执行中会话断掉时同样回到断线态（错误卡片 + 重试），而不是
                // 只留一行失败记录、把输入框留在已死的会话上。
                fun markDisconnectedIfSessionDown() {
                    if (session?.isConnected == false) {
                        sessionRef.set(null)
                        connected = false
                        connectError = appContext.getString(R.string.ssh_terminal_connection_lost)
                    }
                }
                var ch: com.jcraft.jsch.ChannelExec? = null
                try {
                    val execChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch = execChannel
                    // Windows 远端补 UTF-8 输出前缀修中文乱码；平台未知时由命令特征判据兜底。
                    execChannel.setCommand(withUtf8ConsoleEncoding(trimmed, isWindowsRemote))
                    execChannel.connect(10000)
                    val buf = ByteArray(4096)
                    val outBuf = java.io.ByteArrayOutputStream()
                    val errBuf = java.io.ByteArrayOutputStream()
                    val input = execChannel.inputStream
                    val errIn = execChannel.errStream
                    val deadline = System.currentTimeMillis() + 30000
                    while (System.currentTimeMillis() < deadline) {
                        while (input.available() > 0) {
                            val n = input.read(buf)
                            if (n > 0) outBuf.write(buf, 0, n)
                        }
                        while (errIn.available() > 0) {
                            val n = errIn.read(buf)
                            if (n > 0) errBuf.write(buf, 0, n)
                        }
                        if (execChannel.isClosed && input.available() <= 0 && errIn.available() <= 0) break
                        Thread.sleep(100)
                    }
                    val timedOut = !execChannel.isClosed
                    if (timedOut) {
                        runCatching { execChannel.sendSignal("KILL") }
                        runCatching { execChannel.disconnect() }
                    }
                    val stdout = outBuf.toString("UTF-8").trim()
                    val stderr = errBuf.toString("UTF-8").trim()
                    if (stdout.isNotEmpty()) output += "$stdout\n"
                    if (stderr.isNotEmpty()) output += "stderr: $stderr\n"
                    if (timedOut) {
                        output += appContext.getString(R.string.ssh_terminal_command_timeout) + "\n"
                    } else {
                        output += "[exit=${execChannel.exitStatus}]\n"
                    }
                } catch (e: JSchException) {
                    output += appContext.getString(R.string.ssh_terminal_exec_failed, e.message) + "\n"
                    markDisconnectedIfSessionDown()
                } catch (e: IOException) {
                    output += appResources.getString(R.string.ssh_terminal_exec_failed, e.message) + "\n"
                    markDisconnectedIfSessionDown()
                } finally {
                    runCatching { ch?.disconnect() }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("SSH · $hostName")
                        Surface(
                            shape = CircleShape,
                            color =
                                when {
                                    connectError != null -> MaterialTheme.colorScheme.errorContainer
                                    connected -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                            contentColor =
                                when {
                                    connectError != null -> MaterialTheme.colorScheme.onErrorContainer
                                    connected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                        ) {
                            Text(
                                text =
                                    when {
                                        connectError != null -> stringResource(R.string.ssh_terminal_status_failed)
                                        connected -> stringResource(R.string.ssh_terminal_status_connected)
                                        else -> stringResource(R.string.ssh_terminal_status_connecting)
                                    },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (connected) {
                        IconButton(
                            onClick = {
                                runCatching { sessionRef.getAndSet(null)?.disconnect() }
                                connected = false
                                connectError = appContext.getString(R.string.ssh_terminal_disconnected)
                            },
                        ) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.ssh_terminal_disconnect))
                        }
                    }
                    if (output.isNotBlank()) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(output)) },
                        ) {
                            Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.ssh_terminal_copy_output))
                        }
                        IconButton(
                            onClick = { output = "" },
                        ) {
                            Icon(HugeIcons.Clean, contentDescription = stringResource(R.string.ssh_terminal_clear_output))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            connectError?.let { err ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                connectError = null
                                connected = false
                                connectNonce++
                            },
                        ) {
                            Text(stringResource(R.string.ssh_terminal_retry))
                        }
                    }
                }
            }

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                ) {
                    SelectionContainer {
                        LazyColumn(
                            state = outputListState,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(outputLines, key = { index, _ -> index }) { _, line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = JetbrainsMono,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            if (connected) {
                val quickCommands =
                    if (isWindowsRemote == true) SSH_QUICK_COMMANDS_WINDOWS else SSH_QUICK_COMMANDS_POSIX
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickCommands.forEach { preset ->
                        AssistChip(
                            onClick = { runCmd(preset) },
                            label = {
                                Text(
                                    text = preset,
                                    fontFamily = JetbrainsMono,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = { Text(stringResource(R.string.ssh_terminal_input_placeholder)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions =
                            KeyboardActions(
                                onSend = {
                                    runCmd(command)
                                    command = ""
                                },
                            ),
                    )
                    Button(
                        onClick = {
                            runCmd(command)
                            command = ""
                        },
                        enabled = command.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ssh_terminal_execute))
                    }
                }
            }
        }
    }

    pendingHostKey?.let { pending ->
        HostKeyConfirmDialog(
            address = pending.address,
            actual = pending.actual,
            expected = pending.expected,
            onConfirm = {
                pendingHostKey = null
                connectError = null
                scope.launch { connect(trust = pending) }
            },
            onDismiss = {
                pendingHostKey = null
                connectError = appContext.getString(R.string.workspace_ssh_host_key_missing)
                AppLog.w("SshTerm", "connect: host key not trusted for ${pending.host}")
            },
        )
    }
}

/** 等待用户确认的主机密钥：[expected] 为 null 表示首次连接，否则是已信任密钥被替换 */
private data class PendingTerminalHostKey(
    val host: String,
    val address: String,
    val actual: SshHostKey,
    val expected: SshHostKey?,
)

/**
 * 包装文件持久化的 known_hosts 仓库：严格校验（未知 / 变更的密钥一律拒绝），
 * 同时记下对方出示的密钥，供确认弹窗展示指纹；信任后由调用方写入 known_hosts 再重连。
 */
private class VerifyingHostKeyRepository(
    private val delegate: HostKeyRepository,
) : HostKeyRepository {
    @Volatile
    var unknownKey: SshHostKey? = null
        private set

    /** first 为已信任的旧密钥，second 为对方新出示的密钥 */
    @Volatile
    var changedKey: Pair<SshHostKey, SshHostKey>? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val result = delegate.check(host, key)
        when (result) {
            HostKeyRepository.OK -> {
                unknownKey = null
                changedKey = null
            }
            HostKeyRepository.NOT_INCLUDED -> {
                unknownKey = key.toSshHostKey(host)
                changedKey = null
            }
            HostKeyRepository.CHANGED -> {
                val actual = key.toSshHostKey(host)
                val expected = delegate.getHostKey(host, actual.type)
                    ?.firstOrNull()
                    ?.let { SshHostKey(it.type, it.key) }
                if (expected == null) {
                    unknownKey = actual
                    changedKey = null
                } else {
                    unknownKey = null
                    changedKey = expected to actual
                }
            }
        }
        return result
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = delegate.add(hostkey, ui)

    override fun remove(host: String, type: String) = delegate.remove(host, type)

    override fun remove(host: String, type: String, key: ByteArray) = delegate.remove(host, type, key)

    override fun getKnownHostsRepositoryID(): String = delegate.knownHostsRepositoryID

    override fun getHostKey(): Array<HostKey> = delegate.hostKey

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = delegate.getHostKey(host, type)
}

/** 从密钥 blob 解析算法名并拼成可算指纹的 [SshHostKey] */
private fun ByteArray.toSshHostKey(host: String): SshHostKey {
    val type = runCatching { HostKey(host, this).type }.getOrDefault("unknown")
    return SshHostKey(type = type, key = Base64.getEncoder().encodeToString(this))
}

/**
 * 把用户确认过的主机密钥写入持久化 known_hosts：同主机同算法的旧记录替换掉，
 * 其他算法的信任记录保留；先写临时文件再原子替换，避免写一半损坏信任库。
 */
private fun trustHostKey(context: Context, host: String, key: SshHostKey) {
    val knownHosts = knownHostsFile(context)
    val parent = knownHosts.parentFile ?: throw IOException("known_hosts has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Could not create known_hosts directory ${parent.absolutePath}")
    }
    val kept = if (knownHosts.exists()) {
        knownHosts.readLines(Charsets.UTF_8).filter { line ->
            // known_hosts(5)：[@标记 …] 主机列表 算法 Base64密钥
            val tokens = line.trim().split(Regex("\\s+")).dropWhile { it.startsWith("@") }
            val hosts = tokens.getOrNull(0).orEmpty().split(",")
            val type = tokens.getOrNull(1).orEmpty()
            !(host in hosts && type == key.type)
        }
    } else {
        emptyList()
    }
    val temp = File(parent, "${knownHosts.name}.tmp-${System.nanoTime()}")
    try {
        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
            kept.forEach { writer.appendLine(it) }
            writer.appendLine("$host ${key.type} ${key.key}")
        }
        replaceFilePreservingTarget(temp, knownHosts)
    } finally {
        if (temp.exists()) runCatching { temp.delete() }
    }
}
