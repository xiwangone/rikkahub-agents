package me.rerere.rikkahub.data.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 凭证的**非敏感元数据**（明文存储，落 `VaultCredentialEntity.metaJson`）。
 *
 * 设计约束（2026-09-22 定）：
 * - **白名单**：只有 [ALLOWED_KEYS] 里的键允许落明文。新增字段必须先加白名单，
 *   否则会被 [encode] 静默丢弃 —— 这是"秘密绝不进明文列"的**强制点**，不靠调用方自觉。
 * - 秘密（apiKey / 私钥 / 口令 / token）一律只进 `valueEncrypted`，不进这里。
 * - 容忍历史与脏数据：空串、非法 JSON、非字符串值 → 忽略，不抛异常。
 */
object CredentialMeta {

    /** 允许写入明文元数据的键（白名单）。 */
    val ALLOWED_KEYS: Set<String> = setOf(
        "endpoint",   // 接口基址
        "path",       // 接口路径
        "header",     // 注入用的请求头名
        "prefix",     // 值前缀（如 "Bearer "）
        "username",   // 账号名（口令仍走密文）
        "algorithm",  // TOTP 算法
        "digits",     // TOTP 位数
        "period",     // TOTP 周期（秒）
    )

    /** 自定义字段键前缀：`custom.<标签>`。值是**明文备注**，禁止放密钥/口令（2026-09-23 定）。 */
    const val CUSTOM_PREFIX = "custom."

    private fun isAllowedKey(key: String): Boolean = key in ALLOWED_KEYS || key.startsWith(CUSTOM_PREFIX)

    private val jsonCodec = Json { prettyPrint = false }

    /** 白名单过滤后序列化；全部为空 → 返回空串（不写 "{}"）。 */
    fun encode(meta: Map<String, String>): String {
        val kept = meta.filter { (k, v) -> isAllowedKey(k) && v.isNotBlank() }
        if (kept.isEmpty()) return ""
        return jsonCodec.encodeToString(
            JsonObject.serializer(),
            JsonObject(kept.mapValues { JsonPrimitive(it.value) }),
        )
    }

    /** 容错解析：空串 / 非法 JSON / 非字符串值 → 忽略对应项。 */
    fun decode(metaJson: String): Map<String, String> {
        if (metaJson.isBlank()) return emptyMap()
        val obj = runCatching { jsonCodec.parseToJsonElement(metaJson) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return obj.mapNotNull { (k, v) ->
            val s = (v as? JsonPrimitive)?.contentOrNull
            if (isAllowedKey(k) && !s.isNullOrBlank()) k to s else null
        }.toMap()
    }

    /** 非白名单键（供写入侧提示；不阻断，只是不落明文）。 */
    fun rejectedKeys(meta: Map<String, String>): Set<String> = meta.keys.filterNot { isAllowedKey(it) }.toSet()
}
