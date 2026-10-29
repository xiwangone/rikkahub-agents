package me.rerere.rikkahub.data.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import me.rerere.rikkahub.data.log.AppLog
import tsnetlib.Tsnetlib
import java.net.Inet6Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把 Android 的系统网络信息喂给内嵌隧道。
 *
 * 为什么需要：API 30 之后普通 App 进程不能 bind `NETLINK_ROUTE`，Go 侧枚举接口会直接
 * `permission denied`，隧道因此启动即失败。这里改用 Java 层的 [NetworkInterface] 与
 * [ConnectivityManager] 拿到同样的信息，按约定 JSON 交给 native 侧的接口快照与默认路由注入点。
 *
 * 约束：
 * - 每次变化的投递顺序固定为 `setInterfaces` → `setDefaultRouteInterface` → `networkChanged`；
 * - 任何失败都只记日志，探测不通不能把隧道启动流程带崩；
 * - 投递全部串行在一个后台线程上，避免并发覆盖 native 快照。
 */
object TsnetPathMonitor {
    private const val TAG = "TsnetPathMonitor"

    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tsnet-path-monitor").apply { isDaemon = true }
    }

    @Volatile
    private var connectivity: ConnectivityManager? = null

    @Volatile
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 注册默认网络监听，并**先主动喂一次**：冷启动时首个回调可能迟迟不来，
     * 而隧道在这段时间里就需要一份可用的接口快照。重复调用无副作用。
     */
    fun start(context: Context) {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            AppLog.w(TAG, "no connectivity service, tunnel will not see network changes")
            return
        }
        connectivity = cm
        if (started.compareAndSet(false, true)) {
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = feed("available")

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                    feed("link-changed")

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    feed("capabilities-changed")

                override fun onLost(network: Network) = feed("lost")
            }
            runCatching { cm.registerDefaultNetworkCallback(listener) }
                .onSuccess { callback = listener }
                .onFailure { AppLog.w(TAG, "register default network callback failed: ${it.message}", it) }
        }
        // 无论注册成败都先喂一次：没有回调也比没有快照强。
        feed("initial")
    }

    /** 注销监听；之后可以再次 [start]。投递线程保持存活，重启隧道时复用。 */
    fun stop() {
        val listener = callback
        callback = null
        started.set(false)
        if (listener != null) {
            runCatching { connectivity?.unregisterNetworkCallback(listener) }
                .onFailure { AppLog.w(TAG, "unregister failed: ${it.message}", it) }
        }
        connectivity = null
    }

    /** 当前默认出口接口名；无默认网络时为空串（native 侧据此判定"离线"）。 */
    private fun defaultRouteInterface(): String = runCatching {
        val cm = connectivity ?: return@runCatching ""
        val network = cm.activeNetwork ?: return@runCatching ""
        cm.getLinkProperties(network)?.interfaceName.orEmpty()
    }.getOrDefault("")

    private fun feed(reason: String) {
        runCatching {
            executor.execute {
                val json = interfacesJson()
                if (json == null) {
                    AppLog.w(TAG, "feed($reason): interface list unavailable or empty, snapshot untouched")
                    return@execute
                }
                runCatching { Tsnetlib.setInterfaces(json) }
                    .onFailure { AppLog.w(TAG, "setInterfaces failed: ${it.message}", it) }
                val name = defaultRouteInterface()
                runCatching { Tsnetlib.setDefaultRouteInterface(name) }
                    .onFailure { AppLog.w(TAG, "setDefaultRouteInterface failed: ${it.message}", it) }
                runCatching { Tsnetlib.networkChanged() }
                    .onFailure { AppLog.w(TAG, "networkChanged failed: ${it.message}", it) }
                AppLog.i(TAG, "feed($reason): ifaces=${countOf(json)} route=$name")
            }
        }.onFailure { AppLog.w(TAG, "feed($reason) rejected: ${it.message}", it) }
    }

    /** 只用于日志：数一下 JSON 里有几个条目。 */
    private fun countOf(json: String): Int = json.split("\"name\":").size - 1

    /**
     * 生成 native 侧约定的 JSON 数组：name / index / mtu / up / loopback / p2p /
     * multicast / broadcast / addrs（`"ip/prefixLen"`）。
     *
     * 地址要去掉 IPv6 链路本地地址上的 `%wlan0` scope 后缀；某些机型拿不到
     * `networkPrefixLength`（返回 0 或越界），此时按 IPv6=128 / IPv4=32 兜底。
     * 返回 null 表示不可用或空列表——空列表绝不能上报，否则 native 侧会认为设备无接口。
     */
    private fun interfacesJson(): String? = runCatching {
        val builder = StringBuilder("[")
        var count = 0
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val entry = interfaceEntry(iface) ?: continue
            if (count > 0) builder.append(',')
            builder.append(entry)
            count++
        }
        builder.append(']')
        if (count == 0) null else builder.toString()
    }.getOrNull()

    private fun interfaceEntry(iface: NetworkInterface): String? {
        val addresses = runCatching { iface.interfaceAddresses }.getOrNull() ?: return null
        val literals = addresses.mapNotNull { addressLiteral(it) }
        if (literals.isEmpty() && !flagOf { iface.isUp }) return null
        return runCatching {
            buildString {
                append('{')
                append("\"name\":\"").append(escape(iface.name)).append("\",")
                append("\"index\":").append(iface.index).append(',')
                append("\"mtu\":").append(iface.mtu).append(',')
                append("\"up\":").append(flagOf { iface.isUp }).append(',')
                append("\"loopback\":").append(flagOf { iface.isLoopback }).append(',')
                append("\"p2p\":").append(flagOf { iface.isPointToPoint }).append(',')
                append("\"multicast\":").append(flagOf { iface.supportsMulticast() }).append(',')
                append("\"broadcast\":").append(addresses.any { it.broadcast != null }).append(',')
                append("\"addrs\":[")
                append(literals.joinToString(separator = ",") { "\"$it\"" })
                append(']')
                append('}')
            }
        }.getOrNull()
    }

    /** 个别标志位在某些实现上会抛异常，取不到就当 false，不能连带丢掉整个接口。 */
    private inline fun flagOf(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)

    /** `"ip/prefixLen"`；scope 后缀去掉，前缀长度取不到时按协议族兜底。 */
    private fun addressLiteral(entry: InterfaceAddress): String? {
        val address = entry.address ?: return null
        val host = address.hostAddress ?: return null
        val literal = host.substringBefore('%')
        val maxBits = if (address is Inet6Address) 128 else 32
        val bits = entry.networkPrefixLength.toInt()
        return if (bits in 1..maxBits) "$literal/$bits" else "$literal/$maxBits"
    }

    private fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
    }
}
