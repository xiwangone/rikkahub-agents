package me.rerere.ai.provider.providers.backend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.util.shortRequestClient
import me.rerere.common.android.SafeRequestLoggingInterceptor
import me.rerere.common.log.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Backend REST API — 对应 serve 端点的 HTTP 客户端。
 * 使用 kotlinx.serialization 编解码。
 * 增加 Basic Auth / Bearer 认证支持（nginx Basic Auth 前置）。
 */
class BackendApi(
    private val baseUrl: String,
    private val username: String = "",
    private val password: String = "",
    private val token: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // 最小请求日志：只记 host + 状态码 + 耗时，不记 path/query/headers/body
        // （baseUrl 后的路径含会话信息，:common 层无脱敏能力）。
        .addInterceptor(SafeRequestLoggingInterceptor())
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── 发送消息（增量 input，会话由服务端管理）──
    // 返回 false 表示网络失败或服务端非 2xx，调用方不应静默继续
    suspend fun submit(input: String): Boolean = withContext(Dispatchers.IO) {
        post("/submit", buildJsonObject { put("input", JsonPrimitive(input)) })
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
        }.getOrElse {
            AppLogger.e("BackendApi", "解析 /history 失败", it)
            emptyList()
        }
    }

    // ── 获取服务器状态 ──
    suspend fun getStatus(): StatusInfo? = withContext(Dispatchers.IO) {
        val body = get("/status") ?: return@withContext null
        runCatching {
            json.decodeFromString<StatusInfo>(body)
        }.getOrElse {
            AppLogger.e("BackendApi", "解析 /status 失败", it)
            null
        }
    }

    // ── 会话列表 ──
    suspend fun getSessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        val body = get("/sessions") ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<SessionInfo>>(body)
        }.getOrElse {
            AppLogger.e("BackendApi", "解析 /sessions 失败", it)
            emptyList()
        }
    }

    // ── 运行时模型列表 ──
    suspend fun getModels(): List<BackendModelInfo> = withContext(Dispatchers.IO) {
        // 短请求单独收紧超时：共享 client 没有 callTimeout，/models 卡住时模型页最长转圈 10 分钟。
        val body = get("/models", client.shortRequestClient()) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<BackendModelsResponse>(body).models
        }.getOrElse {
            // 解析失败会表现为「模型列表为空 → 回退单模型」，记录原因便于排查
            AppLogger.e("BackendApi", "解析 /models 失败", it)
            emptyList()
        }
    }

    // ── 运行时切换模型 ──
    suspend fun switchModel(ref: String) = withContext(Dispatchers.IO) {
        post("/submit", buildJsonObject { put("input", JsonPrimitive("/model $ref")) })
    }

    // ── 压缩对话 ──
    suspend fun compact() = withContext(Dispatchers.IO) {
        post("/compact")
    }

    // ── 回应工具审批（approval_request 事件）──
    suspend fun approve(
        id: String,
        allow: Boolean,
        session: Boolean = false,
        persist: Boolean = false,
        scope: String = "",
    ) = withContext(Dispatchers.IO) {
        post(
            "/approve",
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("allow", JsonPrimitive(allow))
                put("session", JsonPrimitive(session))
                put("persist", JsonPrimitive(persist))
                put("scope", JsonPrimitive(scope))
            },
        )
    }

    // ── 回答提问卡片（ask_request 事件）──
    suspend fun answer(
        id: String,
        answers: List<JsonObject>,
    ) = withContext(Dispatchers.IO) {
        post(
            "/answer",
            buildJsonObject {
                put("id", JsonPrimitive(id))
                put("answers", JsonArray(answers))
            },
        )
    }

    // ── 内部 HTTP 辅助 ──

    private suspend fun get(path: String, client: OkHttpClient = this.client): String? {
        val request =
            Request.Builder()
                .url(resolveUrl(path))
                .get()
                .applyAuth()
                .build()
        return execute(request, client)
    }

    private suspend fun post(
        path: String,
        body: JsonObject? = null,
    ): Boolean {
        val requestBody =
            (body ?: buildJsonObject { }).toString().toRequestBody(jsonMediaType)
        val request =
            Request.Builder()
                .url(resolveUrl(path))
                .post(requestBody)
                .applyAuth()
                .build()
        return execute(request) != null
    }

    /** baseUrl 未填或非法时给出明确报错，而不是在 toHttpUrl()!! 上崩溃。 */
    private fun resolveUrl(path: String): okhttp3.HttpUrl {
        return baseUrl.toHttpUrlOrNull()?.resolve(path)
            ?: throw IllegalArgumentException("Invalid Backend baseUrl: '$baseUrl'")
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        if (!isAuthSafeTarget()) {
            AppLogger.w(
                "BackendApi",
                "baseUrl 未使用 https 且非本机地址，不发送 Authorization，避免凭据走明文 HTTP",
            )
            return this
        }
        if (token.isNotBlank()) {
            header("Authorization", "Bearer $token")
        } else if (username.isNotBlank() || password.isNotBlank()) {
            val credentials = okhttp3.Credentials.basic(username, password)
            header("Authorization", credentials)
        }
        return this
    }

    /** https 始终可发凭据；http 只允许本机回环地址，局域网/公网 http 一律不发凭据。 */
    private fun isAuthSafeTarget(): Boolean {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        if (url.isHttps) return true
        return url.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
    }

    private suspend fun execute(request: Request, client: OkHttpClient = this.client): String? {
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(
                        "BackendApi",
                        "请求失败：${request.url} HTTP ${response.code}",
                    )
                    return null
                }
                response.body?.string()
            }
        } catch (e: IOException) {
            AppLogger.e("BackendApi", "请求失败：${request.url}", e)
            null
        }
    }
}
