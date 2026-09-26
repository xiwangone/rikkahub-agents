package me.rerere.rikkahub.service.debug

import io.ktor.http.ContentType
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
 * - 配对：短匹配码只在 App 界面显示，POST /debug/pair 换取会话级 token（重启/关闭失效），
 *   token 是唯一通行凭证，匹配码本身不作为请求凭证；
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
) {
    private companion object {
        const val HOST_LOOPBACK = "127.0.0.1"
        const val SESSION_HEADER = "X-Debug-Session"
    }

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    /** 会话 token；服务启动时生成，停止即失效 */
    private val sessionToken = AtomicReference<String?>(null)

    /** 匹配码；仅启动期间存在于内存，配对成功后立即轮换 */
    private val pairingCode = AtomicReference<String?>(null)

    fun start() {
        if (engine != null) return
        sessionToken.set(newSecret())
        pairingCode.set(newSecret(6)) // 6 位短码，UI 展示给用户
        val server = embeddedServer(CIO, port = port, host = host, module = { routes() })
        server.start(wait = false)
        engine = server
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 500)
        engine = null
        sessionToken.set(null)
        pairingCode.set(null)
    }

    /** 当前展示给用户的匹配码（UI 轮询显示；停止服务时置空） */
    fun currentPairingCode(): String? = pairingCode.get()

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

    private fun routes() = routing {
        // 配对：短码换 token（一次性握手；成功后轮换匹配码）
        post("/debug/pair") {
            val remote = call.request.local.remoteHost
            if (allowedNetworks.isNotBlank() && !isRemoteHostAllowed(remote, allowedNetworks)) {
                audit("/debug/pair", remote, HttpStatusCode.Forbidden); call.respondText("{}",
                    ContentType.Application.Json, HttpStatusCode.Forbidden); return@post
            }
            val given = call.receiveText().trim().trim('"')
            val expected = pairingCode.getAndSet(newSecret(6))
            if (expected != null && given == expected) {
                val body = """{"token":"${sessionToken.get()}"}"""
                audit("/debug/pair", remote, HttpStatusCode.OK, "paired")
                call.respondText(body, ContentType.Application.Json)
            } else {
                audit("/debug/pair", remote, HttpStatusCode.Unauthorized, "bad code")
                call.respondText("""{"error":"bad pairing code"}""",
                    ContentType.Application.Json, HttpStatusCode.Unauthorized)
            }
        }

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
                append(",\"listen\":\"$host:$port\"")
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
            val dir = auditDir?.parentFile // app files 根由设置层注入时再调整；此处先占位
            val tail = runCatching {
                dir?.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }?.let { f ->
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
