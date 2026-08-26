package me.rerere.rikkahub.data.ai

import android.content.Context
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.providers.backend.CliCommandExecutor
import me.rerere.rikkahub.data.ai.tools.local.CaptureResult
import me.rerere.rikkahub.data.ai.tools.local.execOneShot
import me.rerere.rikkahub.data.ai.tools.local.resolveHostAuth
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
 * - sshHostId 非空 → SSH 到该主机执行（复用 SshHostRepository + resolveHostAuth + execOneShot）
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
            is CaptureResult.Timeout -> "CLI 命令执行超时"
            is CaptureResult.Denied -> "CLI 命令执行被拒绝（Termux 未授权）"
            is CaptureResult.OtherError -> "CLI 命令执行失败：${result.message}"
        }
    }

    private suspend fun executeSsh(hostName: String, command: String): String {
        val host = sshHostRepository.getByName(hostName) ?: return "SSH 主机 $hostName 不存在"
        val auth = resolveHostAuth(host, vaultRepository) ?: return "SSH 主机 $hostName 无可用凭证"
        val payload =
            runCancellableSshOp(SSH_TIMEOUT_MS) { sessionRef ->
                execOneShot(context, host.host, host.port, host.user, auth, command, SSH_TIMEOUT_MS.toInt(), sessionRef)
            }
        val stdout = payload["stdout"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!stdout.isNullOrBlank()) return stdout
        val stderr = payload["stderr"]?.jsonPrimitive?.contentOrNull?.trim()
        if (!stderr.isNullOrBlank()) return stderr
        return payload["error"]?.jsonPrimitive?.contentOrNull ?: "SSH 执行无输出"
    }
}
