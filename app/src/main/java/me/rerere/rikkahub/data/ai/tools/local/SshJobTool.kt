package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.vault.CredentialVaultRepository

/**
 * 后台任务轮询（2026-09-03）。配合 ssh_exec / ssh_exec_saved 的 background=true 使用：
 * - POSIX 主机：nohup 已 detach，轮询看进程是否还活 + 尾巴输出不可得（重定向 /dev/null）
 * - Windows 主机：Start-Process 输出落 C:\Users\Public\rikkahub_bg_*.log，
 *   轮询读日志尾部即可非阻塞拿进度（不占 SSH 长连接）
 *
 * 非阻塞设计：每次 poll 是一次独立短 SSH exec，立即返回，不持有长会话——
 * AI 发 background 任务 → 干别的 → 隔几轮 poll 拿增量，对话不卡。
 */
fun sshJobPollTool(
    context: Context,
    repo: SshHostRepository,
    vaultRepository: CredentialVaultRepository,
): Tool = Tool(
    name = "ssh_job_poll",
    description = "Poll a background job launched via ssh_exec/ssh_exec_saved with background=true. " +
        "On Windows hosts the job's output is written to a log file returned as [bg_log_path]; pass " +
        "that path here to tail it. Also reports whether the job process is still alive. Returns " +
        "immediately (single short SSH exec) — designed for non-blocking polling.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string"); put("description", "Saved host name") })
                put("log_path", buildJsonObject { put("type", "string"); put("description", "Log file path from the background call's [bg_log_path] (Windows jobs). Optional for POSIX.") })
                put("lines", buildJsonObject { put("type", "integer"); put("description", "Tail lines to read, default 20") })
                put("process_hint", buildJsonObject { put("type", "string"); put("description", "Optional process name/pattern to check liveness, e.g. 'gradle' or 'java'. Omit to only tail log.") })
            },
            required = listOf("name"),
        )
    },
    execute = { input ->
        val p = input.jsonObject
        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
        val logPath = p["log_path"]?.jsonPrimitive?.contentOrNull
        val lines = p["lines"]?.jsonPrimitive?.intOrNull ?: 20
        val procHint = p["process_hint"]?.jsonPrimitive?.contentOrNull
        val h = repo.getByName(name)
            ?: return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "no saved host: $name") }.toString()
            ))
        val auth = resolveHostAuth(h, vaultRepository)
        if (auth == null) {
            return@Tool listOf(UIMessagePart.Text(
                buildJsonObject { put("error", "saved host has no usable credentials") }.toString()
            ))
        }
        val isWindows = (logPath?.startsWith("C:") == true) ||
            listOf("pc", "win", "windows").any { h.name.lowercase().contains(it) }

        val pollCmd = if (isWindows) {
            val parts = mutableListOf<String>()
            // 日志尾部（若给了路径且存在）
            logPath?.let { lp ->
                parts += "if(Test-Path '$lp'){Get-Content '$lp' -Tail $lines}else{'[log-not-found] $lp'}"
            }
            // 进程存活检查（可选）
            procHint?.let { ph ->
                parts += "if(Get-Process $ph -ErrorAction SilentlyContinue){'[alive]'}else{'[exited]'}"
            }
            if (parts.isEmpty()) parts += "'[no log path given]'"
            "powershell -NoProfile -Command \"" + parts.joinToString("; ") + "\""
        } else {
            val parts = mutableListOf<String>()
            logPath?.let { lp -> parts += "tail -n $lines $lp 2>/dev/null || echo '[no-log]'" }
            procHint?.let { ph -> parts += "pgrep -f $ph >/dev/null && echo '[alive]' || echo '[exited]'" }
            if (parts.isEmpty()) parts += "echo '[no log path given]'"
            parts.joinToString("; ")
        }

        // 复用 execOneShot（短超时 20s，轮询不该长等）
        val payload = runCancellableSshOp(20_000L) { sessionRef ->
            execOneShot(context, h.host, h.port, h.user, auth, pollCmd, 20_000, sessionRef, null)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
