package me.rerere.rikkahub.data.vault

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 凭证输出掩码器（P0）。
 *
 * 目标：AI 工具输出中即使意外出现凭证明文，也不让其进入 LLM 上下文。
 * 策略（按用户定稿 2026-08-13）：
 * - **只对密钥库（Vault）内容掩码**——掩码字典 = 密钥库全部凭证的明文值（按规范名称索引）
 * - **随加随掩**——refresh() 时按凭证表版本比对，新增/修改凭证自动纳入，无需改代码
 * - 缓存化——凭证未变更时复用内存字典，避免每次全量解密
 * - 不做格式正则猜测（避免误伤、避免掩码库外内容）
 *
 * 安全：掩码发生在 App 进程内、内存态，解密值不落盘、不进 AI 上下文。
 */
/** 一条凭证对应的掩码规则。exact=true 按完整值精确替换；私钥类(exact=false)靠 PEM 结构正则兜底。 */
data class SecretRule(
    val value: String,
    val exact: Boolean,
)

object SecretMasker {
    private const val MASK = "***"

    // 非敏感短值下限：名称不含敏感词且短于此长度不掩（chat_id/alias 等，防包名式误伤）
    private const val MIN_LEN = 9

    // 敏感名称词：命中即无条件掩（无视长度——6 位密码也掩，解决"短密码明文进上下文"）
    private val SENSITIVE_WORDS = listOf(
        "PASSWORD", "PWD", "PASS", "TOKEN", "SECRET", "APIKEY", "API_KEY",
        "AUTH", "PRIVATE", "KEY",
    )

    // PEM 私钥结构：BEGIN..END PRIVATE KEY 块（DOTALL 容忍 base64 折行，兜住字典精确匹配漏网）
    private val PEM_PRIVATE_KEY = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // 公钥值形态：以这些开头的是公钥（可公开，不掩——authorized_keys 本就公开贴）
    private val PUBLIC_KEY_PREFIX = listOf(
        "ssh-rsa ", "ssh-ed25519 ", "ecdsa-sha2-", "ssh-dss ",
        "sk-ssh-ed25519 ", "sk-ssh-ecdsa-",
    )

    // 缓存：凭证表版本（max updatedAt）→ 掩码规则
    @Volatile
    private var cachedVersion: Long = -1

    @Volatile
    internal var cachedRules: List<SecretRule> = emptyList()

    /** 刷新掩码规则（suspend）。凭证未变更时走缓存。 */
    suspend fun refresh(repository: CredentialVaultRepository) {
        val entries = repository.getAll()
        val version = entries.maxOfOrNull { it.updatedAt } ?: 0L
        if (version == cachedVersion) return
        cachedVersion = version
        cachedRules = entries.mapNotNull { e ->
            val v = repository.decryptValue(e)?.trim() ?: return@mapNotNull null
            classify(e.name, v)
        }
    }

    /**
     * 分类（元数据 + 结构 + 长度三层）：
     * - 公钥值（ssh-rsa/ed25519/ecdsa 开头单行）→ null（不掩，非机密）
     * - 私钥（值含 PRIVATE KEY 头尾）→ exact=false（靠结构正则，容忍折行）
     * - 名称命中敏感词（PWD/TOKEN/KEY/...）→ exact=true，无条件掩（无视长度）
     * - 其余：长度 ≥ MIN_LEN 才掩（保底，防包名式误伤 chat_id/alias）
     */
    internal fun classify(name: String, value: String): SecretRule? {
        val n = name.uppercase()
        if (PUBLIC_KEY_PREFIX.any { value.startsWith(it) }) return null
        if (value.contains("PRIVATE KEY-----")) {
            return SecretRule(value, exact = false)
        }
        if (SENSITIVE_WORDS.any { it in n }) {
            return SecretRule(value, exact = true)
        }
        return if (value.length >= MIN_LEN) SecretRule(value, exact = true) else null
    }

    /** 掩码文本（同步；调用方需先 refresh 保证规则最新）。 */
    fun mask(text: String): String = mask(text, cachedRules)

    /** 掩码文本：PEM 私钥结构正则 + 精确值替换。公钥/非敏感短值不受影响。 */
    fun mask(text: String, rules: Collection<SecretRule>): String {
        var out = text
        // 1) 结构层：私钥块全掩（容忍折行/多行，兜住精确匹配漏网）
        out = PEM_PRIVATE_KEY.replace(out, MASK)
        // 2) 精确层：exact 规则全量替换（敏感类无条件，含 6 位短密码）
        rules.filter { it.exact }.forEach { rule ->
            out = out.replace(rule.value, MASK)
        }
        return out
    }
}

/** 密钥库全部凭证的掩码规则（随加随掩；复用 refresh 缓存避免重复解密）。内存态，用完即弃。 */
internal suspend fun allVaultSecrets(repository: CredentialVaultRepository): List<SecretRule> {
    SecretMasker.refresh(repository)
    return SecretMasker.cachedRules
}

/**
 * vault_http_exec — 带 Vault 凭证调用 HTTP(S) API（P1）。
 *
 * 安全模型（与 vault_ssh_exec 一致）：
 * - 凭证在 App 进程内解密并注入请求头；AI 只拿到响应
 * - 每次调用需人工审批（needsApproval = true），审批面展示 URL + 凭证名
 * - 响应体经 SecretMasker 掩码（防响应中夹带其他凭证明文）
 * - 审计记录（logAccess）
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

    val method = (o["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        .takeIf { it in setOf("GET", "POST", "PUT", "PATCH", "DELETE") }
        ?: return fail("method 只支持 GET/POST/PUT/PATCH/DELETE")
    val url = o["url"]?.jsonPrimitive?.contentOrNull?.trim() ?: return fail("url 必填")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return fail("url 必须是 http(s):// 开头")
    }
    val credName = o["credential_name"]?.jsonPrimitive?.contentOrNull ?: return fail("credential_name 必填")
    val scheme = o["auth_scheme"]?.jsonPrimitive?.contentOrNull ?: "Bearer"
    val headerName = o["header_name"]?.jsonPrimitive?.contentOrNull ?: "Authorization"
    val body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val timeout = (o["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 30).toLong().coerceIn(5L, MAX_TIMEOUT_SECONDS)

    val extraHeaders = runCatching {
        val raw = o["extra_headers"]?.jsonPrimitive?.contentOrNull
        if (raw.isNullOrBlank()) emptyMap()
        else kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject.entries.mapNotNull { (k, v) ->
            (k to (v.jsonPrimitive.contentOrNull ?: return@mapNotNull null))
        }.toMap()
    }.getOrElse { return fail("extra_headers 必须是合法 JSON 对象") }

    val entry = repository.getByName(credName) ?: return fail("凭证不存在: $credName（用 vault_credential_names 查看可用名称）")
    val secret = repository.decryptValue(entry) ?: return fail("凭证解密失败: $credName")
    repository.logAccess(credName, "ai-tool", "http_exec")

    return try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .callTimeout(timeout * 2, TimeUnit.SECONDS)
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
                val bodyOut = if (truncated) rawBody.take(MAX_RESPONSE_BODY) + "\n...[截断 ${rawBody.length - MAX_RESPONSE_BODY} chars]" else rawBody
                val maskedBody = SecretMasker.mask(bodyOut, allVaultSecrets(repository))
                val contentType = resp.header("Content-Type") ?: ""
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            append("status=$code")
                            if (contentType.isNotBlank()) append("\ncontent-type=$contentType")
                            if (maskedBody.isNotBlank()) append("\n--- body ---\n$maskedBody")
                            append("\n（凭证已注入请求头，未出现在本次输出中）")
                        },
                    ),
                )
            }
        }
    } catch (e: Exception) {
        listOf(UIMessagePart.Text("❌ HTTP 请求失败: ${e.message}"))
    }
}
