package me.rerere.rikkahub.data.ai.tools.local

/**
 * 远端平台判定：以 **SSH 协议里服务端的版本串（banner）** 为权威依据。
 *
 * - Windows 版 OpenSSH 报 `SSH-2.0-OpenSSH_for_Windows_9.5`；
 * - Linux 侧报 `SSH-2.0-OpenSSH_9.6` / `SSH-2.0-dropbear_2020.81` 等，都不含 `for_Windows`。
 *
 * JSch 建连后 `Session.getServerVersion()` 即可读到，**不需要额外执行探测命令**。
 * 拿不到 banner（null / 空串）时返回 `null`，由调用方回退到命令特征判据
 * （[looksLikeWindowsCommand]）—— 判据本身不可靠，故只作兜底。
 *
 * ⚠ 时序限制：命令包装通常发生在**建连之前**（`execOneShot` 收到的是已包装好的命令），
 * 那时还读不到 banner → 目前只有"在同一函数里先连后包"的路径（如 `vault_ssh_exec`）
 * 能当次即准；其余路径需后续把 host:port 接进包装点并回填缓存。
 */
internal object RemotePlatform {

    /** 服务端版本串里的 Windows 标识（大小写无关）。 */
    private const val WINDOWS_MARKER = "for_windows"

    /** 缓存键：同一主机大小写/首尾空白差异不应产生两条记录。 */
    fun cacheKey(host: String, port: Int): String = "${host.trim().lowercase()}:$port"

    /** 由服务端版本串判定是否 Windows 远端；无法判定返回 `null`（调用方回退）。 */
    fun isWindowsServer(serverVersion: String?): Boolean? {
        val v = serverVersion?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return null
        return v.contains(WINDOWS_MARKER)
    }
}
