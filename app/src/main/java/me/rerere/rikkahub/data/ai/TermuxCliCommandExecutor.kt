package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.providers.backend.CliCommandExecutor
import me.rerere.rikkahub.data.ai.tools.local.CaptureResult
import me.rerere.rikkahub.data.ai.tools.local.RemotePlatform
import me.rerere.rikkahub.data.ai.tools.local.probePreferredWindows
import me.rerere.rikkahub.data.ai.tools.local.execOneShot
import me.rerere.rikkahub.data.ai.tools.local.withUtf8ConsoleEncoding
import me.rerere.rikkahub.data.ai.tools.local.HostAuthResolution
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuthDetailed
import me.rerere.rikkahub.data.ai.tools.local.runCancellableSshOp
import me.rerere.rikkahub.data.ai.tools.local.runCommandCapture
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
private const val SSH_TIMEOUT_MS = 60_000L

/**
 * [CliCommandExecutor] 实现：CLI 后端（backendType=cli）的「生成」= 执行一条命令行工具。
 *
 * - sshHostId 空 → 手机本地 Termux 执行（Termux RUN_COMMAND 服务）
 * - sshHostId 非空 → SSH 到该主机执行（复用 SshHostRepository + resolveHostAuthDetailed + execOneShot）
 */
class TermuxCliCommandExecutor(
    private val context: Context,
    private val sshHostRepository: SshHostRepository,
    private val vaultRepository: CredentialVaultRepository,
) : CliCommandExecutor {
    override suspend fun execute(command: String, prompt: String, sshHostId: String?): String =
        if (sshHostId.isNullOrBlank()) {
            executeTermux(command)
        } else {
            executeSsh(sshHostId, command)
        }

    private suspend fun executeTermux(command: String): String {
        val result =
            runCommandCapture(
                ctx = context,
                executable = TERMUX_BASH,
                arguments = arrayOf("-c", command),
                workingDir = TermuxDefaults.DEFAULT_WORKING_DIR,
            )
        return when (result) {
            is CaptureResult.Success ->
                if (result.stdout.isNotBlank()) result.stdout.trim() else result.stderr.trim()
            is CaptureResult.Timeout -> "CLI command execution timed out"
            is CaptureResult.Denied -> "CLI command execution denied (Termux not authorized)"
            is CaptureResult.OtherError -> "CLI command execution failed: ${result.message}"
        }
    }

    private suspend fun executeSsh(hostName: String, command: String): String {
        val host = sshHostRepository.getByName(hostName) ?: return "SSH host $hostName does not exist"
        // 带原因解析：报错能区分“名字不存在/解不开/未授权”（旧写法只报“无可用凭证”）
        val auth = when (val r = resolveHostAuthDetailed(host, vaultRepository)) {
            is HostAuthResolution.Ready -> r.auth
            is HostAuthResolution.Unusable -> return r.reason
        }
        // 实体平台优先，探针/ banner 缓存次之（探针优先，真实执行更可靠；与 ssh_exec_saved 同口径；
        // 本路径无全局默认来源）；execOneShot 只收已包装好的命令，故平台在此处用于包装，轮换开关直接透传。
        val resolvedWindows = RemotePlatform.resolveWindows(
            RemotePlatform.platformFromStored(host.platform),
            probePreferredWindows(context, host.host, host.port, host.user),
            null,
        )
        val effectiveCommand = withUtf8ConsoleEncoding(command, resolvedWindows)
        val payload =
            runCancellableSshOp(SSH_TIMEOUT_MS) { sessionRef ->
                execOneShot(context, host.host, host.port, host.user, auth, effectiveCommand, SSH_TIMEOUT_MS.toInt(), sessionRef, allowHostKeyRotation = host.allowHostKeyRotation)
            }
        val stdout = payload["stdout"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!stdout.isNullOrBlank()) return stdout
        val stderr = payload["stderr"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!stderr.isNullOrBlank()) return stderr
        return payload["error"]?.jsonPrimitive?.contentOrNull ?: "SSH execution produced no output"
    }
}
