package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import me.rerere.rikkahub.data.ai.tools.local.VerifyingHostKeyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.HostAuthResolution
import me.rerere.rikkahub.data.ai.tools.local.ProcessProxyCommand
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatform
import me.rerere.rikkahub.data.ai.tools.local.installSshIdentity
import me.rerere.workspace.ssh.JumpHostProxy
import me.rerere.rikkahub.data.ai.tools.local.knownHostsFile
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.preferredAuthentications
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuthDetailed
import me.rerere.rikkahub.data.ai.tools.local.splitProxyCommand
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.sync.replaceFilePreservingTarget
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.extensions.workspace.HostKeyConfirmDialog
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionClient
import me.rerere.rikkahub.ui.pages.extensions.workspace.TERMINAL_TRANSCRIPT_ROWS
import me.rerere.rikkahub.ui.pages.extensions.workspace.ShellTerminalTransport
import me.rerere.rikkahub.ui.pages.extensions.workspace.SshTerminalExitRow
import me.rerere.rikkahub.ui.pages.extensions.workspace.TerminalExtraKeysBar
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalViewClient
import me.rerere.rikkahub.ui.pages.extensions.workspace.writeText
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.ssh.SshHostKey
import org.koin.compose.koinInject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

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
 * SSH 终端页：常驻交互式终端（PTY shell + 终端模拟器）。
 *
 * 建连沿用原来的主机实体凭证与主机密钥确认流程；连上后在同一条连接上开
 * 一个带 PTY 的登录 shell，接到终端模拟器上。键盘输入、Ctrl+C、尺寸变化、
 * ssh 的问答、vim 这类全屏程序都走终端本身，不再按条跑命令。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Suppress("LocalContextGetResourceValueCall")
@Composable
fun SshTerminalPage(hostName: String) {
    val hostRepo: SshHostRepository = koinInject()
    val vaultRepo: CredentialVaultRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var connectNonce by remember { mutableStateOf(0) }
    // 远端是否 Windows：建连成功后由显式平台（主机设置 > 全局默认）或服务端 banner 算出；
    // null 表示尚未判定，快捷命令栏回退到命令特征判据。
    var isWindowsRemote by remember { mutableStateOf<Boolean?>(null) }
    // 平台未判定时用户在页内手动选一次：快捷命令栏按手动值走。
    // 只有未判定时才显示切换；一旦 banner/显式设置判定出来，就以判定为准。
    var platformOverride by remember { mutableStateOf<Boolean?>(null) }
    val isWindowsEffective = platformOverride ?: isWindowsRemote
    var pendingHostKey by remember { mutableStateOf<PendingTerminalHostKey?>(null) }
    var terminalFinished by remember { mutableStateOf(false) }
    var terminalSession by remember { mutableStateOf<TerminalSession?>(null) }
    val terminalSessionRef = remember { AtomicReference<TerminalSession?>(null) }
    val terminalClientRef = remember { AtomicReference<WorkspaceTerminalSessionClient?>(null) }
    val sessionRef = remember { AtomicReference<Session?>(null) }
    // 跳板 session：目标断开后必须显式断它，否则跳板机会残留一条会话
    val jumpSessionRef = remember { AtomicReference<Session?>(null) }
    val viewClient = remember { WorkspaceTerminalViewClient(appContext) }
    var controlDown by remember { mutableStateOf(false) }
    var altDown by remember { mutableStateOf(false) }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown
    val terminalTextSizePx = with(LocalDensity.current) {
        LocalSettings.current.displaySetting.terminalTextSize.sp.roundToPx()
    }
    val terminalTypeface =
        remember(appContext) {
            ResourcesCompat.getFont(appContext, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        }

    fun finishTerminalSession() {
        terminalSessionRef.getAndSet(null)?.finishIfRunning()
        terminalClientRef.getAndSet(null)?.let { it.terminalView = null }
        terminalSession = null
        terminalFinished = false
    }

    fun createTerminalSession(jschSession: Session, address: String) {
        finishTerminalSession()
        // 每个终端会话配自己的 client：旧会话的结束回调可能晚于新会话的建立，
        // 回调只认自己绑定的会话，避免旧会话的结束把新会话误判成已退出。
        val holder = AtomicReference<TerminalSession?>(null)
        val client =
            WorkspaceTerminalSessionClient(
                context = appContext,
                onTitleUpdated = {},
                onFinished = {
                    if (terminalSessionRef.get() === holder.get()) {
                        terminalFinished = true
                        connected = false
                    }
                },
            )
        val transport =
            ShellTerminalTransport(address) { columns, rows ->
                openSshHostShell(jschSession, columns, rows)
            }
        val terminal = TerminalSession(transport, TERMINAL_TRANSCRIPT_ROWS, client)
        holder.set(terminal)
        terminalClientRef.set(client)
        terminalSessionRef.set(terminal)
        terminalSession = terminal
    }

    DisposableEffect(hostName) {
        onDispose {
            finishTerminalSession()
            viewClient.terminalView = null
            runCatching { sessionRef.getAndSet(null)?.disconnect() }
            runCatching { jumpSessionRef.getAndSet(null)?.disconnect() }
        }
    }

    // 建连入口：首次进入时调用；用户在指纹弹窗上点信任后，带上待确认的密钥再调一次，
    // 先把密钥写入 known_hosts 再重连。
    suspend fun connect(trust: PendingTerminalHostKey? = null) {
        withContext(Dispatchers.IO) {
            connectError = null
            connected = false
            terminalFinished = false
            finishTerminalSession()
            runCatching { sessionRef.getAndSet(null)?.disconnect() }
            runCatching { jumpSessionRef.getAndSet(null)?.disconnect() }
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
            val authResolution = resolveHostAuthDetailed(host, vaultRepo, appContext)
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
                    // 跳板（jumpHost 存已保存主机名）：用独立 JSch 连跳板，避免与目标身份互相覆盖；
                    // 目标 session 走跳板 direct-tcpip，按原始 host:port 寻址（不做隧道解析）。
                    val jumpSession = host.jumpHost?.trim()?.takeIf { it.isNotEmpty() }?.let { jh ->
                        val jEntity = hostRepo.getByName(jh)
                            ?: error("Jump host '$jh' does not exist")
                        val jAuth = when (val r = resolveHostAuthDetailed(jEntity, vaultRepo, appContext)) {
                            is HostAuthResolution.Ready -> r.auth
                            is HostAuthResolution.Unusable -> error("Jump host '$jh': ${r.reason}")
                        }
                        val jumpJsch = newJSch(appContext)
                        val (jhHost, jhPort) = if (!jAuth.proxyCommand.isNullOrBlank()) {
                            jEntity.host to jEntity.port
                        } else {
                            me.rerere.workspace.ssh.TunnelRouter.resolve(jEntity.host, jEntity.port)
                        }
                        val js = jumpJsch.getSession(jEntity.user, jhHost, jhPort)
                        if (jhHost != jEntity.host) js.setHostKeyAlias(jEntity.host)
                        js.setConfig("StrictHostKeyChecking", "yes")
                        js.setConfig("ServerAliveInterval", "30")
                        jAuth.password?.let { js.setPassword(it) }
                        installSshIdentity(jumpJsch, jAuth)
                        js.connect(10_000)
                        jumpSessionRef.set(js)
                        js
                    }
                    // 代理命令自己负责寻址：配了 proxyCommand 时不走隧道解析。
                    val proxyCommand = auth.proxyCommand
                    val (dialHost, dialPort) = if (jumpSession != null || !proxyCommand.isNullOrBlank()) {
                        host.host to host.port
                    } else {
                        me.rerere.workspace.ssh.TunnelRouter.resolve(host.host, host.port)
                    }
                    val session = jsch.getSession(host.user, dialHost, dialPort)
                    if (jumpSession != null) {
                        session.setProxy(JumpHostProxy(jumpSession, 30_000L))
                    }
                    // 拨号地址可能是隧道分配的本地地址，主机密钥仍按用户填的主机名比对，
                    // 否则同一主机的密钥变更会被当成新主机放过。
                    if (dialHost != host.host) session.setHostKeyAlias(host.host)
                    // 严格校验：只认 known_hosts 里已信任的密钥；未知 / 变更的密钥
                    // 由 VerifyingHostKeyRepository 记下并走指纹确认弹窗，绝不静默信任。
                    session.setConfig("StrictHostKeyChecking", "yes")
                    session.setConfig("ServerAliveInterval", "30")
                    session.setConfig("ServerAliveCountMax", "3")
                    session.setConfig("PreferredAuthentications", preferredAuthentications(auth))
                    auth.password?.let { session.setPassword(it) }
                    // 证书 / 私钥 / 口令统一走 installSshIdentity：证书先校验（过期、未生效、
                    // 缺私钥都在这里失败），无凭证时不加载任何身份（none 认证）。
                    installSshIdentity(jsch, auth)
                    if (!proxyCommand.isNullOrBlank()) {
                        val argv = try {
                            splitProxyCommand(proxyCommand, host.host, host.port)
                        } catch (e: IllegalArgumentException) {
                            throw JSchException("invalid proxy command: ${e.message}", e)
                        }
                        session.setProxy(ProcessProxyCommand(argv))
                    }
                    session.connect(10000)
                    sessionRef.set(session)
                    connected = true
                    // 平台判定：主机显式设置 > 服务端 banner（建连时获取）> 全局默认；决定快捷命令栏用哪套。
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
                return@withContext
            }
            // 连接已建立，在主线程建终端会话（终端模拟器只在主线程接管视图）；
            // 离开页面后连接已被清掉时不再建，避免建好的会话无处挂载。
            // 建会话本身不做网络操作（打开 shell 通道在终端的会话线程里进行，
            // 失败时由终端的结束回调走「已退出」浮层），这里不需要再包一层捕获。
            val liveSession = sessionRef.get()
            if (liveSession != null && liveSession.isConnected) {
                withContext(Dispatchers.Main) {
                    if (sessionRef.get() === liveSession) {
                        createTerminalSession(liveSession, "${host.user}@${host.host}:${host.port}")
                    }
                }
            }
        }
    }

    LaunchedEffect(hostName, connectNonce) {
        connect()
    }

    fun reconnect() {
        connectError = null
        connected = false
        connectNonce++
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
                                        terminalFinished -> stringResource(R.string.ssh_terminal_exited)
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
                                finishTerminalSession()
                                runCatching { sessionRef.getAndSet(null)?.disconnect() }
                                runCatching { jumpSessionRef.getAndSet(null)?.disconnect() }
                                connected = false
                                terminalFinished = true
                            },
                        ) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.ssh_terminal_disconnect))
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
                    // 按键盘动画的终点高度一次调整终端尺寸，而不是每帧发一次尺寸变化，
                    // 否则终端行数在键盘动画期间来回抖。
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
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
                            onClick = { reconnect() },
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
                color = Color.Black,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            TerminalView(viewContext, null).apply {
                                isFocusable = true
                                isFocusableInTouchMode = true
                                setTextSize(terminalTextSizePx)
                                setTypeface(terminalTypeface)
                                setTerminalViewClient(viewClient)
                                terminalSession?.let { attachSession(it) }
                                terminalClientRef.get()?.terminalView = this
                                viewClient.terminalView = this
                                if (terminalSession != null) {
                                    post { viewClient.focusAndShowKeyboard() }
                                }
                            }
                        },
                        update = { terminalView ->
                            terminalClientRef.get()?.terminalView = terminalView
                            viewClient.terminalView = terminalView
                            val session = terminalSession
                            if (session != null && terminalView.currentSession !== session) {
                                terminalView.setTextSize(terminalTextSizePx)
                                terminalView.setTypeface(terminalTypeface)
                                terminalView.setTerminalViewClient(viewClient)
                                terminalView.attachSession(session)
                                terminalView.onScreenUpdated()
                                terminalView.post { viewClient.focusAndShowKeyboard() }
                            }
                        },
                    )
                    if (terminalSession == null && connectError == null && !terminalFinished) {
                        Text(
                            text = stringResource(R.string.ssh_terminal_status_connecting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (terminalFinished) {
                        SshTerminalExitRow(onReopen = { reconnect() })
                    }
                }
            }

            val terminalLive = terminalSession != null && !terminalFinished
            if (terminalLive) {
                // 平台未判定时默认给 Linux 命令，连 Windows 主机点了也没用，
                // 页内又没地方改：给一个小的 Linux/Windows 切换。
                if (isWindowsRemote == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.ssh_terminal_platform_unknown),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = platformOverride == false,
                            onClick = { platformOverride = false },
                            label = { Text(stringResource(R.string.setting_ssh_platform_linux)) },
                        )
                        FilterChip(
                            selected = platformOverride == true,
                            onClick = { platformOverride = true },
                            label = { Text(stringResource(R.string.setting_ssh_platform_windows)) },
                        )
                    }
                }
                // 快捷命令现在是往终端里发文本（带回车），不再按条执行：交互问答
                // 与全屏程序都能继续用。
                val quickCommands =
                    if (isWindowsEffective == true) SSH_QUICK_COMMANDS_WINDOWS else SSH_QUICK_COMMANDS_POSIX
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickCommands.forEach { preset ->
                        AssistChip(
                            onClick = { terminalSession?.writeText("$preset\n") },
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
                TerminalExtraKeysBar(
                    controlDown = controlDown,
                    altDown = altDown,
                    onControlToggle = { controlDown = !controlDown },
                    onAltToggle = { altDown = !altDown },
                    onSendText = { terminalSession?.writeText(it) },
                )
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
