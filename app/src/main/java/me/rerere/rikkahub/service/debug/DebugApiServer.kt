package me.rerere.rikkahub.service.debug

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.request.receiveText
import me.rerere.rikkahub.utils.isBindAvailableWithRetry
import me.rerere.rikkahub.utils.isRemoteHostAllowed
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * AI 调试 API（实验性，默认关闭）：外部 AI 读取 App 状态与日志，供设备侧验证与排障。
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

    /** 审计写盘锁：判长度 → 轮转 → 写串行，防并发请求同时轮转丢行 */
    private val auditLock = Any()

    /** token 提供者：可由管理器热替换（凭证库引用/自定义口令）；返回 null = 自动生成 */
    var tokenProvider: () -> String? = { null }

    /** 命令执行器（管理器注入；null = exec 端点返回 unavailable）。完全控制通道 */
    var commandRunner: ((command: String, timeoutMs: Int) -> JsonObject)? = null

    /**
     * /debug/info 附加自举信息（管理器注入）：版本、运行时长、shizuku 状态、口令来源等。
     * 返回的键直接合并进响应顶层；为空 map 时响应与旧行为兼容。
     */
    var infoProvider: () -> JsonObject = { buildJsonObject { } }

    /**
     * /debug/health 附加子系统检查（管理器注入：shizuku 可用性等）。
     * 每项形如 {"name":…, "ok":…, "detail":…}，与 server 自身的目录/端口检查合并。
     */
    var healthProvider: (() -> List<JsonObject>)? = null

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
            synchronized(auditLock) {
                runCatching {
                    dir.mkdirs()
                    val f = File(dir, "audit.log")
                    // 简单轮转：超 2MB 归档为 .1（单代覆盖），防审计文件随调试会话无限增长
                    if (f.length() > 2_000_000) {
                        File(dir, "audit.log.1").delete()
                        f.renameTo(File(dir, "audit.log.1"))
                    }
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** 常数时间比较：令牌走网线可被计时侧信道逐字节猜出（String== 首字节不等即提前返回） */
    private fun tokenEquals(actual: String?, expected: String): Boolean =
        actual != null && MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

    private fun authorized(call: io.ktor.server.application.ApplicationCall): Boolean {
        val remote = call.request.local.remoteHost
        if (allowedNetworks.isNotBlank() && !isRemoteHostAllowed(remote, allowedNetworks)) return false
        val token = sessionToken.get() ?: return false
        // Authorization 必须带 "Bearer " 前缀才认后半段；裸口令不认（与旧行为一致）
        val bearer = call.request.headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
        return tokenEquals(call.request.headers[SESSION_HEADER], token)
            || tokenEquals(bearer, token)
    }

    /** 统一 401：审计留痕 + 结构化响应；返回 true 表示已放行 */
    private suspend fun authed(
        call: io.ktor.server.application.ApplicationCall,
        endpoint: String,
    ): Boolean {
        if (authorized(call)) return true
        audit(endpoint, call.request.local.remoteHost, HttpStatusCode.Unauthorized)
        call.respondText(
            """{"error":"unauthorized"}""",
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return false
    }

    private fun Application.routes() = routing {
        routeInfo()
        routeLogs()
        routeLogcat()
        routeHealth()
        routeExec()
    }

    private fun Routing.routeInfo() {
        get("/debug/info") {
            if (!authed(call, "/debug/info")) return@get
            val body = buildJsonObject {
                put("ok", true)
                put("app", "Yao")
                put("debugApi", "experimental")
                put("listen", "$listenHost:$boundPort")
                infoProvider().forEach { (k, v) -> put(k, v) }
            }
            audit("/debug/info", call.request.local.remoteHost, HttpStatusCode.OK)
            call.respondText(body.toString(), ContentType.Application.Json)
    }

    // 只读日志：App files/logs 下 *.log 尾部（FileLogSink 落盘处）
    //   ?list=1        列出全部日志文件（名称/大小/修改时间）
    //   ?file=<名称>   指定文件尾部（仅按名匹配既有清单，无路径穿越面）
    //   ?filter=<正则> 行级过滤（非法正则 400）
    //   ?max=<字节>    尾部字节数上限
    }

    private fun Routing.routeLogs() {
        get("/debug/logs") {
            if (!authed(call, "/debug/logs")) return@get
            val files = logsDir?.listFiles()
                ?.filter { it.isFile && it.extension == "log" }
                .orEmpty()
            if (call.request.queryParameters["list"] == "1") {
                val arr = buildJsonArray {
                    files.sortedByDescending { it.lastModified() }.forEach { f ->
                        add(buildJsonObject {
                            put("name", f.name)
                            put("size", f.length())
                            put("mtime", f.lastModified())
                        })
                    }
                }
                audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.OK, "list")
                call.respondText(
                    buildJsonObject {
                        put("ok", true)
                        put("files", arr)
                    }.toString(),
                    ContentType.Application.Json,
                )
                return@get
            }
            val max = call.request.queryParameters["max"]?.toIntOrNull()?.coerceIn(1, 256_000) ?: 32_000
            val filter = call.request.queryParameters["filter"]?.let { raw ->
                runCatching { Regex(raw) }.getOrNull()
            }
            if (call.request.queryParameters["filter"] != null && filter == null) {
                audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.BadRequest, "bad filter")
                call.respondText("""{"error":"bad_filter"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val requested = call.request.queryParameters["file"]
            val target = if (requested != null) {
                // 仅匹配既有文件清单里的名字：不拼路径，天然无穿越
                files.firstOrNull { it.name == requested } ?: run {
                    audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.NotFound, "file_not_found")
                    call.respondText("""{"error":"file_not_found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    return@get
                }
            } else {
                files.maxByOrNull { it.lastModified() }
            }
            val tail = runCatching {
                target?.let { f ->
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        val len = raf.length()
                        val readLen = minOf(len, max.toLong()).toInt()
                        if (len > readLen) raf.seek(len - readLen)
                        val buf = ByteArray(readLen)
                        raf.readFully(buf)
                        String(buf, Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            val filtered = if (filter != null && tail != null) {
                tail.lines().filter { filter.containsMatchIn(it) }.joinToString("\n")
            } else tail
            audit("/debug/logs", call.request.local.remoteHost, HttpStatusCode.OK, requested ?: "latest")
            call.respondText(
                """{"ok":true,"file":${json(target?.name)},"tail":${json(filtered)}}""",
                ContentType.Application.Json,
            )
    }

    // 设备 logcat 尾部：走与 exec 相同的 Shizuku 通道；专用端点免拼 shell、
    // 无 Shizuku 时返回结构化 unavailable（外部 AI 可据此降级到 /debug/logs）
    }

    private fun Routing.routeLogcat() {
        get("/debug/logcat") {
            if (!authed(call, "/debug/logcat")) return@get
            val runner = commandRunner
            if (runner == null) {
                audit("/debug/logcat", call.request.local.remoteHost, HttpStatusCode.NotImplemented, "no runner")
                call.respondText(
                    """{"error":"exec_unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotImplemented,
                )
                return@get
            }
            val max = call.request.queryParameters["max"]?.toIntOrNull()?.coerceIn(10, 5000) ?: 1000
            audit("/debug/logcat", call.request.local.remoteHost, HttpStatusCode.OK, "-t $max")
            val result = runner("logcat -d -v time -t $max", 15_000)
            call.respondText(result.toString(), ContentType.Application.Json)
    }

    // 子系统健康：外部 AI 先探能力再决定动作（shizuku 可用→logcat/exec；否则只读日志）
    }

    private fun Routing.routeHealth() {
        get("/debug/health") {
            if (!authed(call, "/debug/health")) return@get
            val checks = buildList {
                val dir = auditDir
                add(healthCheck("audit_dir_writable", dir != null && runCatching {
                    dir.mkdirs()
                    java.io.File.createTempFile(".health", ".tmp", dir).delete()
                }.isSuccess, if (dir == null) "not configured" else ""))
                add(healthCheck("logs_dir_readable", logsDir?.isDirectory == true, if (logsDir == null) "not configured" else ""))
                add(healthCheck("bound", boundPort > 0, "port=$boundPort"))
                healthProvider?.invoke()?.forEach { add(it) }
            }
            val allOk = checks.all {
                it["ok"] == JsonPrimitive(true)
            }
            audit("/debug/health", call.request.local.remoteHost, HttpStatusCode.OK)
            call.respondText(
                buildJsonObject {
                    put("ok", allOk)
                    put("checks", buildJsonArray { checks.forEach { add(it) } })
                }.toString(),
                ContentType.Application.Json,
            )
    }

    // 通用命令执行（完全控制通道）：默认经 Shizuku 以 shell uid 运行（≈adb shell），
    // Shizuku 不可用时返回结构化错误；需要 root 由调用方在命令内显式 su -c，默认不提权
    }

    private fun Routing.routeExec() {
        post("/debug/exec") {
            if (!authed(call, "/debug/exec")) return@post
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


    private fun healthCheck(name: String, ok: Boolean, detail: String = "") = buildJsonObject {
        put("name", name)
        put("ok", ok)
        put("detail", detail)
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
