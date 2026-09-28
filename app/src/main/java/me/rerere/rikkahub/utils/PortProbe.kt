package me.rerere.rikkahub.utils

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

/**
 * 端口可绑定性探测（App 内集中入口，所有监听服务共用同一语义）。
 *
 * 与 ktor CIO 引擎一致使用 [ServerSocketChannel]：它默认**不开** SO_REUSEADDR，探测结果与引擎
 * 随后真正 bind 的结果相同。不要用 `java.net.ServerSocket` —— 它默认开 SO_REUSEADDR，会在上一进程
 * 的监听 socket 尚未回收时报「可用」，引擎随后 bind 失败并把异常抛在自己的协程里（不经调用方
 * 异常链），表现为启动即崩（2026-09-28 真机崩溃快照实证）。
 *
 * 只探测、不申请：App 内自撞应由生命周期驱动串行化消除（见 DebugApiManager），外部占用无法根治，
 * 调用方按「重试等待 → 向后避让 → 报错并展示实际端口」的顺序处理。
 */
fun isBindAvailable(port: Int, host: String = "127.0.0.1"): Boolean = runCatching {
    val channel = ServerSocketChannel.open()
    try {
        channel.bind(InetSocketAddress(host, port))
    } finally {
        channel.close()
    }
}.isSuccess

/**
 * 探测并重试 [attempts] 次（间隔 [delayMs]）：给上一进程残留的监听 socket 一点回收时间，
 * 避免一有冲突就立刻漂移到别的端口（端口稳定对"外部要连进来"的服务很重要）。
 */
fun isBindAvailableWithRetry(
    port: Int,
    host: String = "127.0.0.1",
    attempts: Int = 3,
    delayMs: Long = 400L,
): Boolean {
    repeat(attempts) { attempt ->
        if (isBindAvailable(port, host)) return true
        if (attempt < attempts - 1) runCatching { Thread.sleep(delayMs) }
    }
    return false
}
