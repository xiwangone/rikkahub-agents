package me.rerere.rikkahub.ui.pages.setting

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.HostAuthResolution
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuthDetailed
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.data.vault.ensureTrailingNewline
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.koin.compose.koinInject
import java.io.IOException

private val SSH_QUICK_COMMANDS =
    listOf(
        "ls -la",
        "pwd",
        "df -h",
        "free -m",
        "uname -a",
        "docker ps",
    )

/**
 * SSH 终端页（Material 3 Expressive 深色终端面板 + 快捷命令栏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LocalContextGetResourceValueCall")
@Composable
fun SshTerminalPage(hostName: String) {
    val hostRepo: SshHostRepository = koinInject()
    val vaultRepo: CredentialVaultRepository = koinInject()
    val appContext = LocalContext.current
    val appResources = LocalResources.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val sessionRef = remember { java.util.concurrent.atomic.AtomicReference<com.jcraft.jsch.Session?>(null) }
    val outputScrollState = rememberScrollState()

    DisposableEffect(hostName) {
        onDispose {
            runCatching { sessionRef.getAndSet(null)?.disconnect() }
        }
    }

    LaunchedEffect(output.length) {
        outputScrollState.animateScrollTo(outputScrollState.maxValue)
    }

    LaunchedEffect(hostName) {
        withContext(Dispatchers.IO) {
            val host = hostRepo.getByName(hostName)
            if (host == null) {
                AppLog.w("SshTerm", "connect: host not found $hostName")
                connectError = appContext.getString(R.string.ssh_terminal_host_not_found, hostName)
                return@withContext
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
            try {
                val jsch = newJSch(appContext)
                val (dialHost, dialPort) = me.rerere.workspace.ssh.TunnelRouter.resolve(host.host, host.port)
                val session = jsch.getSession(host.user, dialHost, dialPort)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("ServerAliveInterval", "30")
                session.setConfig("ServerAliveCountMax", "3")
                auth.password?.let { session.setPassword(it) }
                auth.privateKey?.let {
                    jsch.addIdentity("ssh-term-$hostName", it.ensureTrailingNewline().toByteArray(Charsets.UTF_8), null, null)
                }
                session.connect(10000)
                sessionRef.set(session)
                connected = true
                output = appContext.getString(R.string.ssh_terminal_connected, host.user, host.host, host.port) + "\n"
                AppLog.i("SshTerm", "connected ${host.user}@${host.host}:${host.port}")
            } catch (e: JSchException) {
                connectError = appContext.getString(R.string.ssh_terminal_connect_failed, e.message)
                AppLog.w("SshTerm", "connect failed: ${e.message}")
            }
        }
    }

    fun runCmd(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isBlank()) return
        output += "\n$ $trimmed\n"
        scope.launch {
            withContext(Dispatchers.IO) {
                val session = sessionRef.get() ?: return@withContext
                var ch: com.jcraft.jsch.ChannelExec? = null
                try {
                    val execChannel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch = execChannel
                    execChannel.setCommand(trimmed)
                    execChannel.connect()
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
                    val stdout = outBuf.toString("UTF-8").trim()
                    val stderr = errBuf.toString("UTF-8").trim()
                    if (stdout.isNotEmpty()) output += "$stdout\n"
                    if (stderr.isNotEmpty()) output += "stderr: $stderr\n"
                    output += "[exit=${execChannel.exitStatus}]\n"
                } catch (e: JSchException) {
                    output += appContext.getString(R.string.ssh_terminal_exec_failed, e.message) + "\n"
                } catch (e: IOException) {
                    output += appResources.getString(R.string.ssh_terminal_exec_failed, e.message) + "\n"
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
                                        connectError != null -> stringResource(R.string.workspace_detail_shell_broken)
                                        connected -> stringResource(R.string.workspace_detail_shell_ready)
                                        else -> stringResource(R.string.workspace_detail_loading)
                                    },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (output.isNotBlank()) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(output)) },
                        ) {
                            Icon(HugeIcons.Copy01, contentDescription = null)
                        }
                        IconButton(
                            onClick = { output = "" },
                        ) {
                            Icon(HugeIcons.Clean, contentDescription = null)
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
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF111318),
                contentColor = Color(0xFFE2E8F0),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = output,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = JetbrainsMono,
                            color = Color(0xFFE2E8F0),
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(outputScrollState),
                        )
                    }
                }
            }

            if (connected) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SSH_QUICK_COMMANDS.forEach { preset ->
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
}
