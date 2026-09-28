package me.rerere.rikkahub.service.debug

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.request.receiveText
import io.ktor.server.request.header
import io.ktor.utils.io.core.use
import me.rerere.rikkahub.utils.isRemoteHostAllowed
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * AI 调试 API（实验性，默认关闭）。
 *
 * 用途：开发期让外部 AI（PC/另一台设备上的助手）远程读取 App 内部状态，
 * 用于真机验证与排障——补上「App 内部状态只能截图」的缺口。
 *
 * 安全模型（与 LocalMcpServer 同族，比 ZeroTermux 式常驻调试口严格）：
 * - 设置里显式开启，默认关闭；UI 需红字大字警告（暴露范围 = 所选网络）；
 * - 监听地址：127.0.0.1（默认，仅本机）/ 虚拟内网地址（Tailscale 等，推荐）/ 0.0.0.0（局域网，最危险）；
 * - CIDR 白名单复用 [isRemoteHostAllowed]；
 * - 凭证：唯一通行凭证是 token（自动生成或引用凭证库/自定义口令），UI 内可查看/复制；
 * - 全部请求审计留痕（时间 / 端点 / 来源 / 结果码）。
 */
class DebugApiServer(
    private val port: Int,
    /** 监听地址；默认仅本机。对外场景由设置层显式传入虚拟内网/局域网地址 */
    private val host: String = HOST_LOOPBACK,
    /** 来源网段白名单（CIDR，逗号分隔；空 = 仅 loopback 语义，建议设置层强制非空） */
    private val allowedNetworks: String = "",
    /** 审计落盘目录（App files/debug-api）；null = 不落盘（不建议） */
    private val auditDir: File? = null,
    /** 日志目录（App files/logs）；null = /debug/logs 返回未配置 */
    private val logsDir: File? = null,
) {
    private companion object {
        const val HOST_LOOPBACK = "127.0.0.1"
        const val SESSION_HEADER = "X-Debug-Session"

        /** 首选端口被占时的向后避让宽度（须保证结果仍落在临时端口段之下） */
        const val PORT_SCAN_RANGE = 8

        /** 单端口探测的重试次数与间隔：给上一进程残留的监听 socket 一点回收时间 */
        const val PROBE_ATTEMPTS = 3
        const val PROBE_DELAY_MS = 400L
    }

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    /** 实际监听端口；未运行为 0 */
    @Volatile
    var boundPort: Int = 0
        private set

    /** 会话 token；服务启动时生成，停止即失效 */
    private val sessionToken = AtomicReference<String?>(null)

    /** token 提供者：可由管理器热替换（凭证库引用/自定义口令）；返回 null = 自动生成 */
    var tokenProvider: () -> String? = { null }

    /** 当前会话 token（未运行返回 null）；供 UI 展示 */
    fun currentToken(): String? = sessionToken.get()

    /**
     * 起服务并返回实际监听端口。首选口先重试等待（残留监听回收），仍不可用才向后避让
     * [PORT_SCAN_RANGE] 个端口；全部失败才抛 [IllegalStateException]（调用方负责复位开关）。
     */
    fun start(preferredPort: Int = port): Int {
        engine?.let { return boundPort }
        sessionToken.set(tokenProvider() ?: newSecret())
        var lastError: Throwable? = null
        for (candidate in preferredPort until preferredPort + PORT_SCAN_RANGE) {
            if (!probeBindable(host, candidate)) continue
            val server = embeddedServer(CIO, port = candidate, host = host, module = { routes() })
            runCatching { server.start(wait = false) }
                .onSuccess {
                    engine = server
                    boundPort = candidate
                    return candidate
                }
                .onFailure { e ->
                    // 引擎协程 bind 失败会同时从调用方抛出：停掉半成品实例，换下一个端口
                    runCatching { server.stop(gracePeriodMillis = 0, timeoutMillis = 100) }
                    lastError = e
                }
        }
        sessionToken.set(null)
        throw IllegalStateException(
            "debug api $host:$preferredPort..${preferredPort + PORT_SCAN_RANGE - 1} unavailable" +
                " (port in use?): ${lastError?.message}",
            lastError,
        )
    }

    /**
     * 端口可用性探测：与引擎同语义用 [java.nio.channels.ServerSocketChannel]（默认不开
     * SO_REUSEADDR）。原先用 java.net.ServerSocket 预检——它默认开 SO_REUSEADDR，会在上一进程
     * 残留监听未回收时「预检通过、引擎真 bind 失败」，等于没拦住（2026-09-28 真机崩在启动后 22ms）。
     */
    private fun probeBindable(host: String, port: Int): Boolean {
        repeat(PROBE_ATTEMPTS) { attempt ->
            val ok = runCatching {
                val channel = java.nio.channels.ServerSocketChannel.open()
                try {
                    channel.bind(java.net.InetSocketAddress(host, port))
                } finally {
                    channel.close()
                }
            }.isSuccess
            if (ok) return true
            if (attempt < PROBE_ATTEMPTS - 1) runCatching { Thread.sleep(PROBE_DELAY_MS) }
        }
        return false
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        engine = null
        boundPort = 0
        sessionToken.set(null)
    }

    private fun newSecret(length: Int = 40): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return buildString { repeat(length) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    private fun audit(endpoint: String, remote: String, status: HttpStatusCode, detail: String = "") {
        val line = "${System.currentTimeMillis()}\t$remote\t$endpoint\t${status.value}\t$detail"
        auditDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                File(dir, "audit.log").appendText(line + "\n")
            }
        }
    }

    private fun authorized(call: io.ktor.server.application.ApplicationCall): Boolean {
        val remote = call.request.local.remoteHost
        if (allowedNetworks.isNotBlank() && !isRemoteHostAllowed(remote, allowedNetworks)) return false
        val token = sessionToken.get() ?: return false
        return call.request.headers[SESSION_HEADER] == token
            || call.request.headers["Authorization"] == "Bearer $token"
    }

    private fun Application.routes() = routing {
        get("/debug/info") {
            if (!authorized(call)) {
                audit("/debug/info", call.request.local.remoteHost, HttpStatusCode.Unauthorized)
                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json,
                    HttpStatusCode.Unauthorized); return@get
            }
            val body = buildString {
                append("{\"ok\":true")
                append(",\"app\":\"rikkahub-agents\"")
                append(",\"debugApi\":\"experimental\"")
                append(",\"listen\":\"$host:$boundPort\"")
                append("}")
            }
            audit("/debug/info", call.request.local.remoteHost, HttpStatusCode.OK)
            call.respondText(body, ContentType.Application.Json)
        }

        // 只读日志：App files/logs 目录下文本尾部（logcat 级别的采集后续再接）
        get("/debug/logs") {
            if (!authorized(call)) {
                audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.Unauthorized)
                call.respondText("""{"error":"unauthorized"}""", ContentType.Application.Json,
                    HttpStatusCode.Unauthorized); return@get
            }
            val max = call.request.queryParameters["max"]?.toIntOrNull()?.coerceIn(1, 256_000) ?: 32_000
            // 固定读 files/logs 下最新的 *.log（FileLogSink 落盘处），固定目录无路径穿越
            val tail = runCatching {
                logsDir?.listFiles()?.filter { it.isFile && it.extension == "log" }?.maxByOrNull { it.lastModified() }?.let { f ->
                    val bytes = f.readBytes()
                    val start = if (bytes.size > max) bytes.size - max else 0
                    String(bytes, start, bytes.size - start)
                }
            }.getOrNull()
            audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.OK)
            call.respondText("""{"ok":true,"tail":${json(tail)}}""", ContentType.Application.Json)
        }
    }

    private fun json(s: String?): String = buildString {
        append('"')
        s?.forEach { c ->
            when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
                '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
