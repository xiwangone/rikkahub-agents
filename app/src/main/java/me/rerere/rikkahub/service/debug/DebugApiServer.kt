package me.rerere.rikkahub.service.debug

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import me.rerere.rikkahub.utils.isBindAvailableWithRetry
import me.rerere.rikkahub.utils.isRemoteHostAllowed
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * AI 调试 API（实验性，默认关闭）：外部 AI 读取 App 状态与日志，供真机验证与排障。
 * 安全：默认关；监听档位仅本机/局域网/自定义 CIDR/全部接口 + CIDR 白名单；token 门禁；审计留痕。
 */
class DebugApiServer(
    private val port: Int,
    /** 审计落盘目录（App files/debug-api）；null = 不落盘（不建议） */
    private val auditDir: File? = null,
    /** 日志目录（App files/logs）；null = /debug/logs 返回未配置 */
    private val logsDir: File? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val HOST_LOOPBACK = "127.0.0.1"
        const val SESSION_HEADER = "X-Debug-Session"

        /** 首选端口被占时的向后避让宽度（须保证结果仍落在临时端口段之下） */
        const val PORT_SCAN_RANGE = 8
    }

    @Volatile
    private var engine: EmbeddedServer<*, *>? = null

    /** 实际监听端口；未运行为 0 */
    @Volatile
    var boundPort: Int = 0
        private set

    /** 绑定地址（start 时生效）：127.0.0.1 仅本机；"::" 双栈（IPv6 + IPv4-mapped） */
    @Volatile
    var listenHost: String = HOST_LOOPBACK

    /** 来源网段白名单（CIDR，逗号分隔）；空 = 不做网段限制，仅 token 门禁 */
    @Volatile
    var allowedNetworks: String = ""

    /** 会话 token；服务启动时生成，停止即失效 */
    private val sessionToken = AtomicReference<String?>(null)

    /** token 提供者：可由管理器热替换（凭证库引用/自定义口令）；返回 null = 自动生成 */
    var tokenProvider: () -> String? = { null }

    /** 命令执行器（管理器注入；null = exec 端点返回 unavailable）。完全控制通道 */
    var commandRunner: ((command: String, timeoutMs: Int) -> JsonObject)? = null

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
            if (!isBindAvailableWithRetry(candidate, listenHost)) continue
            val server = embeddedServer(CIO, port = candidate, host = listenHost, module = { routes() })
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
            "debug api $listenHost:$preferredPort..${preferredPort + PORT_SCAN_RANGE - 1} unavailable" +
                " (port in use?): ${lastError?.message}",
            lastError,
        )
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
                append(",\"listen\":\"$listenHost:$boundPort\"")
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

        // 通用命令执行（完全控制通道）：默认经 Shizuku 以 shell uid 运行（≈adb shell），
        // Shizuku 不可用时返回结构化错误；需要 root 由调用方在命令内显式 su -c，默认不提权
        post("/debug/exec") {
            if (!authorized(call)) {
                audit("/debug/exec", call.request.local.remoteHost, HttpStatusCode.Unauthorized)
                call.respondText(
                    """{"error":"unauthorized"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                ); return@post
            }
            val runner = commandRunner
            val body = runCatching { json.decodeFromString<ExecRequest>(call.receiveText()) }.getOrNull()
            if (runner == null) {
                audit("/debug/exec", call.request.local.remoteHost, HttpStatusCode.NotImplemented, "no runner")
                call.respondText(
                    """{"error":"exec_unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotImplemented,
                ); return@post
            }
                        if (body == null || body.command.isBlank()) {
                audit("/debug/exec", call.request.local.remoteHost, HttpStatusCode.BadRequest, "bad body")
                call.respondText(
                    """{"error":"bad_request"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                ); return@post
            }
            audit("/debug/exec", call.request.local.remoteHost, HttpStatusCode.OK, body.command.take(200))
            val result = runner(body.command, body.timeoutMs.coerceIn(1_000, 600_000))
            call.respondText(result.toString(), ContentType.Application.Json)
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

/** 每命令执行时长上限 10 分钟；app 侧超时由 runner 内部再框 */
@Serializable
internal data class ExecRequest(val command: String, val timeoutMs: Int = 120_000)
