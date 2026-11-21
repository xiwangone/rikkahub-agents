package me.rerere.rikkahub.service.debug

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * /debug 系列端点行为测试（纯 JVM，真起 Ktor CIO 监听随机端口）。
 * 覆盖：鉴权（Bearer 与常数时间比较路径）、富 info 合并、logs 的 list/file/filter/max、
 * logcat 无 runner 的结构化降级、health 检查项聚合。
 */
class DebugApiServerTest {

    private lateinit var auditDir: File
    private lateinit var logsDir: File
    private lateinit var server: DebugApiServer
    private var port = 0
    private val client = HttpClient.newHttpClient()
    private val token = "test-token-0123456789"

    @Before
    fun setUp() {
        auditDir = Files.createTempDirectory("debug-audit").toFile()
        logsDir = Files.createTempDirectory("debug-logs").toFile()
        // 显式定 mtime：最新文件按 lastModified 判定，连续写入的 mtime 可能相同
        val newest = File(logsDir, "app-2026-11-20.log")
        newest.writeText("line1 ERROR boom\nline2 INFO ok\nline3 ERROR again\n")
        val older = File(logsDir, "app-2026-11-19.log")
        older.writeText("old log\n")
        newest.setLastModified(System.currentTimeMillis())
        older.setLastModified(System.currentTimeMillis() - 86_400_000)
        server = DebugApiServer(port = DebugApiManager.PORT, auditDir = auditDir, logsDir = logsDir)
        // 固定口令：server.start 时经 tokenProvider 读取（不设则自动随机生成，全部 401）
        server.tokenProvider = { token }
        server.infoProvider = { buildJsonObject { put("versionName", "test") } }
        val probe = ServerSocket(0).also { port = it.localPort; it.close() }
        port = server.start(preferredPort = port)
    }

    @After
    fun tearDown() {
        runCatching { server.stop() }
        auditDir.deleteRecursively()
        logsDir.deleteRecursively()
    }

    private fun get(path: String, withToken: Boolean = true): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        if (withToken) b.header("X-Debug-Session", token)
        return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun jsonOf(body: String): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject

    @Test
    fun `missing token returns 401 with audit entry`() {
        val r = get("/debug/info", withToken = false)
        assertEquals(401, r.statusCode())
        assertEquals("unauthorized", jsonOf(r.body())["error"].toString().trim('"'))
        // 审计留痕（未授权也记）
        val audit = File(auditDir, "audit.log").readText()
        assertTrue(audit.contains("/debug/info"))
        assertTrue(audit.contains("401"))
    }

    @Test
    fun `wrong token returns 401`() {
        val r = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/debug/info"))
                .header("X-Debug-Session", "wrong-token").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, r.statusCode())
    }

    @Test
    fun `bearer token accepted`() {
        val r = client.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/debug/info"))
                .header("Authorization", "Bearer $token").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, r.statusCode())
    }

    @Test
    fun `info merges infoProvider and listen`() {
        val r = get("/debug/info")
        assertEquals(200, r.statusCode())
        val j = jsonOf(r.body())
        assertEquals("Yao", j["app"].toString().trim('"'))
        assertEquals("test", j["versionName"].toString().trim('"'))
        assertTrue(j["listen"].toString().contains(port.toString()))
    }

    @Test
    fun `logs default tails newest file`() {
        val r = get("/debug/logs")
        assertEquals(200, r.statusCode())
        val j = jsonOf(r.body())
        assertEquals("app-2026-11-20.log", j["file"].toString().trim('"'))
        assertTrue(j["tail"].toString().contains("line1 ERROR boom"))
    }

    @Test
    fun `logs filter keeps only matching lines`() {
        val r = get("/debug/logs?filter=ERROR")
        assertEquals(200, r.statusCode())
        val tail = jsonOf(r.body())["tail"].toString()
        assertTrue(tail.contains("line1 ERROR boom"))
        assertTrue(tail.contains("line3 ERROR again"))
        assertFalse(tail.contains("line2 INFO ok"))
    }

    @Test
    fun `logs bad filter returns 400`() {
        assertEquals(400, get("/debug/logs?filter=%5B").statusCode())
    }

    @Test
    fun `logs file param selects named file and 404 on unknown`() {
        val r = get("/debug/logs?file=app-2026-11-19.log")
        assertEquals(200, r.statusCode())
        assertTrue(jsonOf(r.body())["tail"].toString().contains("old log"))
        assertEquals(404, get("/debug/logs?file=nope.log").statusCode())
    }

    @Test
    fun `logs list returns file entries`() {
        val r = get("/debug/logs?list=1")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("app-2026-11-20.log"))
        assertTrue(body.contains("app-2026-11-19.log"))
    }

    @Test
    fun `logcat without runner returns structured unavailable`() {
        val r = get("/debug/logcat")
        assertEquals(501, r.statusCode())
        assertTrue(r.body().contains("exec_unavailable"))
    }

    @Test
    fun `health aggregates server checks and provider extras`() {
        server.healthProvider = {
            listOf(buildJsonObject { put("name", "shizuku_usable"); put("ok", false); put("detail", "n/a") })
        }
        val r = get("/debug/health")
        assertEquals(200, r.statusCode())
        val body = r.body()
        assertTrue(body.contains("audit_dir_writable"))
        assertTrue(body.contains("shizuku_usable"))
        // 有 false 项 → 总体 ok=false
        assertTrue(body.contains("\"ok\":false"))
    }
}
