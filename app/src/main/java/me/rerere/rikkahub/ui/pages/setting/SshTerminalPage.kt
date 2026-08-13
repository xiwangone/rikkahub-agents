package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuth
import me.rerere.rikkahub.data.ai.tools.local.newJSch
import me.rerere.rikkahub.data.ai.tools.local.SshAuth

/**
 * SSH 终端页（P3 用户操作面 MVP）。
 *
 * 命令行式：连接保存的主机（Vault 凭证引用/明文兼容），输入命令 → exec 通道执行 → 显示输出。
 * 后续可升级为完整 TerminalView 交互终端。
 */
@Composable
fun SshTerminalPage(hostName: String) {
    val hostRepo: SshHostRepository = koinInject()
    val vaultRepo: CredentialVaultRepository = koinInject()
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var sessionRef = remember { java.util.concurrent.atomic.AtomicReference<com.jcraft.jsch.Session?>(null) }

    LaunchedEffect(hostName) {
        withContext(Dispatchers.IO) {
            val host = hostRepo.getByName(hostName)
            if (host == null) { connectError = "主机不存在: $hostName"; return@withContext }
            val auth = resolveHostAuth(host, vaultRepo)
            if (auth == null) { connectError = "无可用凭证（vault ref: ${host.vaultCredentialRef ?: "none"}）"; return@withContext }
            try {
                val jsch = newJSch(appContext)
                val session = jsch.getSession(host.user, host.host, host.port)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("ServerAliveInterval", "30")
                session.setConfig("ServerAliveCountMax", "3")
                auth.password?.let { session.setPassword(it) }
                auth.privateKey?.let { jsch.addIdentity("ssh-term-${hostName}", it.toByteArray(Charsets.UTF_8), null, null) }
                session.connect(10000)
                sessionRef.set(session)
                connected = true
                output = "已连接 ${host.user}@${host.host}:${host.port}\n"
            } catch (e: Exception) {
                connectError = "连接失败: ${e.message}"
            }
        }
    }

    fun runCmd(cmd: String) {
        if (cmd.isBlank()) return
        output += "> $cmd\n"
        scope.launch {
            withContext(Dispatchers.IO) {
                val session = sessionRef.get() ?: return@withContext
                try {
                    val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand(cmd)
                    ch.connect()
                    val buf = ByteArray(4096)
                    val outBuf = java.io.ByteArrayOutputStream()
                    val errBuf = java.io.ByteArrayOutputStream()
                    val `in` = ch.inputStream
                    val errIn = ch.errStream
                    val deadline = System.currentTimeMillis() + 30000
                    while (System.currentTimeMillis() < deadline) {
                        while (`in`.available() > 0) { val n = `in`.read(buf); if (n > 0) outBuf.write(buf, 0, n) }
                        while (errIn.available() > 0) { val n = errIn.read(buf); if (n > 0) errBuf.write(buf, 0, n) }
                        if (ch.isClosed && `in`.available() <= 0 && errIn.available() <= 0) break
                        Thread.sleep(100)
                    }
                    val stdout = outBuf.toString("UTF-8").trim()
                    val stderr = errBuf.toString("UTF-8").trim()
                    output += if (stdout.isNotEmpty()) "$stdout\n" else ""
                    if (stderr.isNotEmpty()) output += "stderr: $stderr\n"
                    output += "exit=${ch.exitStatus}\n"
                    ch.disconnect()
                } catch (e: Exception) {
                    output += "执行失败: ${e.message}\n"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.LargeFlexibleTopAppBar(
                title = { Text("SSH · $hostName") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior(),
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            connectError?.let {
                Text("❌ $it", color = MaterialTheme.colorScheme.error)
            }
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            )
            if (connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        placeholder = { Text("输入命令，回车执行") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedButton(onClick = { runCmd(command); command = "" }) {
                        Text("执行")
                    }
                }
            }
        }
    }
}
