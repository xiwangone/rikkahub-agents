package me.rerere.ai.provider.providers.reasonix

/**
 * CLI 后端命令执行器——跨模块解耦接口。
 *
 * ai module 只定义接口（不依赖 termux / SSH 主机），app module 负责实现
 * （复用 termux_run_command 本地执行，或 SshHostRepository 远程执行），经 DI 注入
 * [ReasonixProvider]。
 *
 * CLI 后端（backendType=cli）的「生成」= 执行一条命令行工具，把用户提示词喂进去，
 * 拿到标准输出作为回复。与 reasonix 的 SSE / custom 的 HTTP 是三种并列的接入协议。
 */
interface CliCommandExecutor {
    /**
     * 执行 CLI 命令，返回输出文本。
     *
     * @param sshHostId 目标 SSH 主机名（SshHostRepository 的 name）；null/空白 = 手机本地 Termux 执行。
     */
    suspend fun execute(command: String, prompt: String, sshHostId: String? = null): String
}
