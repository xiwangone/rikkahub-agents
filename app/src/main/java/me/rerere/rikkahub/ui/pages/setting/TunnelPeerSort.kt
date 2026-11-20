package me.rerere.rikkahub.ui.pages.setting

/** IP 排序键：每段补零到 3 位，字符串比较即数字顺序（100.066.066.009 < 100.066.066.010）；非数字段按 0 计。 */
private fun ipSortKey(ip: String): String =
    ip.split(".").joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(3, '0') }

/** 对端的排序用 IP：首个含 3 个点的地址；没有 IPv4 地址时排最后。聊天页卡片预览复用。 */
internal fun TunnelPeer.sortIp(): String =
    ips.firstOrNull { it.count { c -> c == '.' } == 3 }?.let(::ipSortKey) ?: "999.999.999.999"

/** 一个 tailnet 对端。 */
internal data class TunnelPeer(
    val hostName: String,
    val dnsName: String,
    val ips: List<String>,
    val online: Boolean,
    val canBeExitNode: Boolean,
    /** 直连地址；为空表示走中继。 */
    val curAddr: String = "",
    /** 中继所在的 DERP 区域（如 hkg / sz）。 */
    val relay: String = "",
    /** 最近 2 分钟内有流量；在线但无流量即为空闲（未建立实时路径）。缺省 true，兼容旧版 Go 库。 */
    val active: Boolean = true,
    /** Go 侧 peerStatus 的 OS（如 linux / windows / android）；旧版 Go 库没有该字段时为空。 */
    val os: String = "",
    /** 本机合成行（不在 Go 的 peers 列表里，弹窗里置顶）；对端一律 false。 */
    val isSelf: Boolean = false,
)

/** tsnet 状态快照（来自 TsnetBridge.statusJson）。 */
internal data class TunnelStatus(
    val running: Boolean = false,
    val selfIps: List<String> = emptyList(),
    val dnsName: String = "",
    /** 控制面侧的终端名称（控制台可改；本机只读展示）。 */
    val hostName: String = "",
    val peers: List<TunnelPeer> = emptyList(),
    val acceptRoutes: Boolean = false,
    val exitNodeIp: String = "",
)

/**
 * 聊天页卡片预览：只列在线、最近活跃排前、再按 IP，取前 5 台。
 * Go 侧 peers 是 map 遍历顺序，不排每次刷新都会乱跳。
 */
internal fun List<TunnelPeer>.previewPeers(): List<TunnelPeer> =
    filter { it.online }
        .sortedWith(
            compareByDescending<TunnelPeer> { it.active }
                .thenBy { it.sortIp() },
        )
        .take(5)

/**
 * 弹窗行第二行：有域名时 "短名  IP"，无域名时只有 IP；有 OS 时末尾加 " · os"。
 * OS 为空时输出与旧逻辑逐字一致。
 */
internal fun TunnelPeer.dialogSecondLine(): String {
    val name = hostName.ifBlank { ips.firstOrNull().orEmpty() }
    val ip = ips.firstOrNull().orEmpty()
    val base = if (dnsName.isBlank()) ip else "$name  $ip"
    return if (os.isBlank() || base.isBlank()) base else "$base · $os"
}

/**
 * 本机合成行：本机不在 Go 的 peers 列表里，用快照里的本机信息拼一行放弹窗最前面。
 * App 只跑在 Android 上，本机 OS 固定写 android。快照为空、或连本机名和 IP 都没有时返回 null。
 */
internal fun selfPeer(status: TunnelStatus?): TunnelPeer? {
    if (status == null) return null
    if (status.hostName.isBlank() && status.dnsName.isBlank() && status.selfIps.isEmpty()) return null
    return TunnelPeer(
        hostName = status.hostName,
        dnsName = status.dnsName,
        ips = status.selfIps,
        online = true,
        canBeExitNode = false,
        active = true,
        os = "android",
        isSelf = true,
    )
}

/**
 * 弹窗完整列表：本机在前、其余按 IP 数字序（短名兜底），与现状一致；
 * 本机为空时输出与现状逐字一致。
 */
internal fun dialogPeers(peers: List<TunnelPeer>, self: TunnelPeer?): List<TunnelPeer> {
    val sorted = peers.sortedWith(
        compareBy<TunnelPeer> { it.sortIp() }
            .thenBy { it.dnsName.trimEnd('.').ifBlank { it.hostName }.lowercase() },
    )
    return if (self == null) sorted else listOf(self) + sorted
}
