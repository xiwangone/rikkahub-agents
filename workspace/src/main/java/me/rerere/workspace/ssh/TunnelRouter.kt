package me.rerere.workspace.ssh

/**
 * 隧道拨号路由：把「逻辑目标」映射成「实际拨号目标」。
 *
 * 为什么需要它：SSH 建连在 workspace 模块（[SshConnection]），而内嵌隧道实现在 app 模块
 * （[me.rerere.rikkahub.data.tunnel.TsnetBridge]）；app 依赖 workspace，反向依赖不允许。
 * 所以这里只留一个可注入的回调，由 app 在启动时注册实现。
 *
 * 约定：返回 null 表示"不接管"，调用方按原始 host/port 拨号。
 */
object TunnelRouter {

    /** (host, port) -> 实际拨号用的 (host, port)；null = 不接管。 */
    @Volatile
    private var resolver: ((String, Int) -> Pair<String, Int>?)? = null

    /** 由 app 侧在启动时注册（传 null 可注销）。 */
    fun install(fn: ((String, Int) -> Pair<String, Int>?)?) {
        resolver = fn
    }

    /**
     * 解析实际拨号目标。任何异常都回退到原始目标 —— 隧道出问题不能拖垮 SSH 本身。
     */
    fun resolve(host: String, port: Int): Pair<String, Int> {
        val fn = resolver ?: return host to port
        return runCatching { fn(host, port) }.getOrNull() ?: (host to port)
    }
}
