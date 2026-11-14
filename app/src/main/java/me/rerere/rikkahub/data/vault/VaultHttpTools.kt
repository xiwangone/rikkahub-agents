package me.rerere.rikkahub.data.vault

import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.SafeRequestLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit


/**
 * vault_http_exec — 带 Vault 凭证调用 HTTP(S) API（P1）。
 *
 * 安全模型（与 vault_ssh_exec 一致）：
 * - 凭证在 App 进程内解密并注入请求头；AI 只拿到响应
 * - 每次调用需人工审批（needsApproval = true），审批面展示 URL + 凭证名
 * - 响应体经 SecretMasker 掩码（防响应中夹带其他凭证明文）
 * - 审计记录（logAccess）
 * - 凭证须通过目的地绑定（`metaJson.endpoint`，绑定后请求 URL 必须在基址之下）
 *   与类型校验（须为 api-key / cloud-ak 类型），否则拒绝（P0-2）。
 */
fun vaultHttpExecTool(
    context: Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_http_exec",
    description = buildString {
        append("Call an HTTP(S) API using a credential stored in the vault. ")
        append("The credential value is injected as a request header inside the app process (default Authorization: <scheme> <value>); ")
        append("the AI only receives the response. Use for backend services / APIs that require a token or key. ")
        append("The credential must be an api-key (or cloud-ak) credential, and if the credential has an endpoint bound ")
        append("in its metadata, the URL must be under that endpoint. ")
        append("The response body is masked against credential patterns before returning. Approval required before making the request.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "HTTP method: GET/POST/PUT/PATCH/DELETE (default GET)")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Full http(s) URL of the API endpoint")
                })
                put("credential_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Vault credential name whose value becomes the request header (see vault_credential_names)")
                })
                put("auth_scheme", buildJsonObject {
                    put("type", "string")
                    put("description", "Header value scheme: Bearer / Token / raw (default Bearer; raw = value used as-is)")
                })
                put("header_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Header name to inject the credential into (default Authorization)")
                })
                put("extra_headers", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional JSON object of extra request headers, e.g. {\"Accept\":\"application/json\"}. Cannot override the credential header.")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body (sent as JSON)")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in seconds (default 30, max 120)")
                })
            },
            required = listOf("url", "credential_name"),
        )
    },
    needsApproval = { true },
    execute = { params -> runVaultHttpExec(context, repository, params.jsonObject) },
)

private const val MAX_RESPONSE_BODY = 8192
private const val MAX_TIMEOUT_SECONDS = 120L

private suspend fun runVaultHttpExec(
    context: Context,
    repository: CredentialVaultRepository,
    o: kotlinx.serialization.json.JsonObject,
): List<UIMessagePart> {
    val fail: (String) -> List<UIMessagePart> = { msg -> listOf(UIMessagePart.Text("❌ $msg")) }

    val method = (o.optString("method") ?: "GET").uppercase()
        .takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE") }
        ?: return fail("method only supports GET/POST/PUT/PATCH/DELETE")
    val url = o.optString("url")?.trim() ?: return fail("url is required")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return fail("url must start with http(s)://")
    }
    // 凭据会以 Authorization 头随请求发出：明文 http 会把密钥泄露在链路上 → 仅允许环回地址走 http
    if (url.startsWith("http://")) {
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val loopback = host != null && (
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host.startsWith("127.")
            )
        if (!loopback) {
            return fail("Requests carrying credentials must use https:// (http:// is only allowed for loopback addresses)")
        }
    }
    val credName = o.optString("credential_name") ?: return fail("credential_name is required")
    val scheme = o.optString("auth_scheme") ?: "Bearer"
    val headerName = o.optString("header_name") ?: "Authorization"
    val body = o.optString("body").orEmpty()
    val timeout = (o.optInt("timeout_seconds") ?: 30).toLong().coerceIn(5L, MAX_TIMEOUT_SECONDS)

    val extraHeaders = runCatching {
        val raw = o.optString("extra_headers")
        if (raw.isNullOrBlank()) emptyMap()
        else kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject.entries.mapNotNull { (k, v) ->
            (k to ((v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null))
        }.toMap()
    }.getOrElse { return fail("extra_headers must be a valid JSON object") }

    // 取值统一走单点解析器：存在性 / 解密 / 审计一处实现，失败文案一致
    // HTTP_HEADER 现需会话授权：不传 isAuthorized 会直接被拒（AI 能借此代用户对任意服务发请求）；
    // 且按凭证名过滤——受限会话只能用白名单内的凭证。
    // 名字过滤由下面这道门执行（credName 已定）；解析器的检查只是复核"有会话"，
    // 不再重复按名过滤。
    val sessionManager = VaultSessionManager(context)
    if (!sessionManager.hasActiveAuthorization(credName)) {
        return fail("Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool")
    }
    val resolved =
        CredentialResolver(repository) { sessionManager.hasActiveAuthorization() }
            .resolve(
                credName,
                CredentialPurpose.HTTP_HEADER,
                caller = "ai-tool",
                destination = CredentialDestination.Http(url),
                use = CredentialUse.HTTP_HEADER,
            )
    val secret = (resolved as? CredentialResolution.Granted)?.value ?: return fail(resolved.message)

    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout * 2, TimeUnit.SECONDS)
                // 最小请求日志：只记 host + 状态码 + 耗时。凭据 header 名由用户配置，
                // 未必在脱敏集合里，完整拦截器可能把凭据值记进日志。
                .addInterceptor(SafeRequestLoggingInterceptor())
                .build()
            val reqBuilder = Request.Builder().url(url)
            val headerValue = if (scheme == "raw") secret else "$scheme $secret"
            reqBuilder.header(headerName, headerValue)
            extraHeaders.forEach { (k, v) ->
                if (!k.equals(headerName, ignoreCase = true)) reqBuilder.header(k, v)
            }
            if (body.isNotBlank()) {
                reqBuilder.method(method, body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            } else {
                reqBuilder.method(method, null)
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val rawBody = resp.body?.string() ?: ""
                val truncated = rawBody.length > MAX_RESPONSE_BODY
                val bodyOut = if (truncated) rawBody.take(MAX_RESPONSE_BODY) + "\n...[truncated ${rawBody.length - MAX_RESPONSE_BODY} chars]" else rawBody
                val maskedBody = SecretMasker.mask(bodyOut, allVaultSecrets(repository))
                val contentType = resp.header("Content-Type") ?: ""
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            append("status=$code")
                            if (contentType.isNotBlank()) append("\ncontent-type=$contentType")
                            if (maskedBody.isNotBlank()) append("\n--- body ---\n$maskedBody")
                            append("\n(Credentials were injected into the request headers and do not appear in this output)")
                        },
                    ),
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        listOf(UIMessagePart.Text("❌ HTTP request failed: ${e.message}"))
    }
}
