package me.rerere.ai.provider.providers.reasonix

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Reasonix REST API — 对应 serve 端点的 HTTP 客户端。
 * 移植自 DeepSeek-Reasonix-android `ReasonixApi.kt`（Gson → kotlinx.serialization），
 * 增加 Basic Auth / Bearer 认证支持（nginx Basic Auth 前置）。
 */
class ReasonixApi(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val token: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── 发送消息（增量 input，会话由服务端管理）──
    suspend fun submit(input: String): Boolean = withContext(Dispatchers.IO) {
        post("/submit", buildJsonObject { put("input", JsonPrimitive(input)) })
        true
    }

    // ── 取消当前操作 ──
    suspend fun cancel() = withContext(Dispatchers.IO) {
        post("/cancel")
    }

    // ── 新建会话 ──
    suspend fun newSession() = withContext(Dispatchers.IO) {
        post("/new")
    }

    // ── 恢复会话 ──
    suspend fun resumeSession(path: String) = withContext(Dispatchers.IO) {
        post("/resume", buildJsonObject { put("path", JsonPrimitive(path)) })
    }

    // ── 获取历史消息 ──
    suspend fun getHistory(): List<HistoryMessage> = withContext(Dispatchers.IO) {
        val body = get("/history") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<HistoryMessage>>(body)
        }.getOrElse { emptyList() }
    }

    // ── 获取服务器状态 ──
    suspend fun getStatus(): StatusInfo? = withContext(Dispatchers.IO) {
        val body = get("/status") ?: return@withContext null
        runCatching {
            json.decodeFromString<StatusInfo>(body)
        }.getOrNull()
    }

    // ── 会话列表 ──
    suspend fun getSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        val body = get("/sessions") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<SessionInfo>>(body)
        }.getOrElse { emptyList() }
    }

    // ── 运行时模型列表 ──
    suspend fun getModels(): List<ReasonixModelInfo> = withContext(Dispatchers.IO) {
        val body = get("/models") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<ReasonixModelsResponse>(body).models
        }.getOrElse { emptyList() }
    }

    // ── 运行时切换模型 ──
    suspend fun switchModel(ref: String) = withContext(Dispatchers.IO) {
        post("/submit", buildJsonObject { put("input", JsonPrimitive("/model $ref")) })
    }

    // ── 压缩对话 ──
    suspend fun compact() = withContext(Dispatchers.IO) {
        post("/compact")
    }

    // ── 内部 HTTP 辅助 ──

    private suspend fun get(path: String): String? {
        val request =
            Request.Builder()
                .url(baseUrl.toHttpUrl()!!.resolve(path)!!)
                .get()
                .applyAuth()
                .build()
        return execute(request)
    }

    private suspend fun post(
        path: String,
        body: JsonObject? = null,
    ) {
        val requestBody =
            (body ?: buildJsonObject { }).toString().toRequestBody(jsonMediaType)
        val request =
            Request.Builder()
                .url(baseUrl.toHttpUrl()!!.resolve(path)!!)
                .post(requestBody)
                .applyAuth()
                .build()
        execute(request)
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        } else if (username.isNotBlank() || password.isNotBlank()) {
            val credentials = okhttp3.Credentials.basic(username, password)
            header("Authorization", credentials)
        }
        return this
    }

    private suspend fun execute(request: Request): String? {
        return try {
            val response = client.newCall(request).execute()
            response.body?.string().also { response.close() }
        } catch (e: IOException) {
            null
        }
    }
}
