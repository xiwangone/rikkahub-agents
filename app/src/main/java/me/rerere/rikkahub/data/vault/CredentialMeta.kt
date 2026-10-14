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
        // 非敏感标识（2026-10-07 加）：账号 / AK / 云侧 ID 这类“标识而非秘密”的信息。
        // 此前只能塞描述（不可结构化）或当密文存（引用方与 AI 都读不到）→ 现归入明文元数据。
        "account",        // 账号 / 登录名 / 邮箱
        "access_key_id",  // 云访问密钥 ID（AK）；配套 SK 仍走密文
        "user_id",
        "domain_id",
        "project_id",
        "region",
        // 值指纹（SHA-256 前 16 hex，见 CredentialVaultRepository.fingerprint）：
        // 供“不见值比对”（判重 / 判值是否变过）；高熵密钥不可反推。
        "value_fp",
    )

    /** 自定义字段键前缀：`custom.<标签>`。值是**明文备注**，禁止放密钥/口令（2026-09-23 定）。 */
    const val CUSTOM_PREFIX = "custom."

    /**
     * 白名单内、但**不允许按字段引用**（`$$名字.字段`）的键。
     *
     * `value_fp` 是主值 SHA-256 的前 16 hex，用途是"不见值比对"（判重 / 判值是否变过）。
     * 口令类主值往往是低熵的，指纹可被离线字典反推；一旦能注入到任意位置，
     * "比对用的散列"就变成了"可用的值"。元数据侧照常展示（`vault_credential_meta`），
     * 只是不进引用通道。
     */
    val NON_REFERENCEABLE_KEYS: Set<String> = setOf("value_fp")

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

    /**
     * 按字段引用（`$$名字.字段`）取一个明文值；取不到 → null。
     *
     * 两条规则（顺序有意为之，冲突在此定死）：
     * 1. **裸键优先**：先查 `meta[字段]`（显式拼 `custom.x` 时这一步就命中）；
     * 2. **裸名回退**：字段不以 `custom.` 开头时，再查 `custom.<字段>`。
     *
     * 所以 `region` 与 `custom.region` 同时存在时**以标准键 `region` 为准** ——
     * 标准键是被白名单审查过、类型推荐字段集认得的结构化字段，`custom.*` 只是给人看的备注，
     * 不该劫持引用结果。被 [NON_REFERENCEABLE_KEYS] 拒绝的键只在第一步拦（值取不到），
     * 第二步仍允许命中 `custom.<同名>` 的用户备注 —— 拒的是那条指纹，不是这个名字。
     */
    fun resolveField(meta: Map<String, String>, field: String): String? {
        val key = field.trim()
        if (key.isEmpty()) return null
        if (key !in NON_REFERENCEABLE_KEYS) {
            meta[key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        if (!key.startsWith(CUSTOM_PREFIX)) {
            meta[CUSTOM_PREFIX + key]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /**
     * 一条元数据里**所有可被引用的字段键 → 值**（含 `custom.<标签>` 的裸名回退键）。
     *
     * 值一律经 [resolveField] 得出 —— 同步解析缓存（`VaultProviderKeyRefs`）与异步解析器
     * （`CredentialResolver`）由此**共用同一条字段规则**，不会出现"这边能引、那边不能引"。
     */
    fun referenceableFields(meta: Map<String, String>): Map<String, String> {
        val candidates = HashSet<String>(meta.keys)
        meta.keys.filter { it.startsWith(CUSTOM_PREFIX) }.forEach { candidates += it.removePrefix(CUSTOM_PREFIX) }
        return candidates
            .filter { it.isNotBlank() }
            .sorted()
            .mapNotNull { key -> resolveField(meta, key)?.let { key to it } }
            .toMap()
    }

    /** [referenceableFields] 的 JSON 入口（容错解码，规则同上）。 */
    fun referenceableFields(metaJson: String): Map<String, String> = referenceableFields(decode(metaJson))
}
