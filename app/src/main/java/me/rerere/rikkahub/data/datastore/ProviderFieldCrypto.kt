package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.PROVIDERS
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG_PROVIDER_CRYPTO = "ProviderFieldCrypto"
private const val VAULT_REF_PREFIX = "\$\$"

/**
 * `PROVIDERS` 字段级加解密与迁移编排：
 * - 仅加密敏感凭据字段：`apiKey`、`privateKey`、`password`、`token`、`webBridgePassword`、`customHeaders[].value`
 *   （含 `models[].customHeaders[].value` 与 `models[].providerOverwrite`）；
 * - 保持明文：`id`、`name`、`type`、`providerKey`、`enabled`、`builtIn`、`models[]`、`baseUrl`、
 *   各路径、`balanceOption`、`routing` 等非敏感配置；
 * - 兼容旧版整包密文：外层非 `[` 开头时先解密验证可解析为 JSON 数组再采用；
 * - 坏值保护：单字段 `enc:v1:` 解不开时保留原密文并标记 `degraded`，再保存亦禁止用空串覆盖原密文。
 */
internal fun decodeProvidersFieldEncrypted(raw: String?): List<ProviderSetting> {
    if (raw == null) return emptyList()
    val trimmed = raw.trimStart { it.isWhitespace() || it == '\uFEFF' }
    require(trimmed.isNotEmpty()) { "Persisted providers setting is blank" }
    val jsonText =
        if (trimmed.startsWith("[")) {
            trimmed
        } else {
            val decrypted =
                ProviderCredentialCipher.decrypt(trimmed)
                    ?.trimStart { it.isWhitespace() || it == '\uFEFF' }
                    ?.takeIf { it.startsWith("[") }
                    ?: error("Persisted providers setting is neither a JSON array nor a decryptable legacy provider ciphertext")
            decrypted
        }
    return decodeProvidersTolerant(jsonText).map { it.withDecryptedSecrets() }
}

internal fun encodeProvidersFieldEncrypted(
    providers: List<ProviderSetting>,
    existingDiskRaw: String? = null,
): String {
    captureDegradedSlotsFromDisk(existingDiskRaw)
    val encryptedList = providers.map { it.withEncryptedSecrets() }
    return JsonInstant.encodeToString(encryptedList)
}

internal fun MutablePreferences.putProvidersFieldEncryptedIfChanged(
    previous: Settings?,
    oldValue: List<ProviderSetting>?,
    newValue: List<ProviderSetting>,
) {
    val existing = this[PROVIDERS]
    // 注意：`Settings.init == true` 代表冷启动未完成完整解码的占位态（见 Settings.dummy / loadWarmUiSettings），
    // `init == false` 才是已完整加载的真实态；因此仅在 `!previous.init`（真实态）且值未变、磁盘已是字段级密文时跳过写盘。
    val canSkipWrite =
        previous != null &&
            !previous.init &&
            oldValue == newValue &&
            isAlreadyFieldEncryptedProvidersJson(existing, newValue)
    if (canSkipWrite) return
    val encoded = encodeProvidersFieldEncrypted(newValue, existing)
    if (existing != encoded) {
        this[PROVIDERS] = encoded
    }
}

/**
 * 判定磁盘存量字符串是否已是无需迁移的新版字段级 JSON：
 * 1) 必须以 `[` 开头（非旧版整包密文）；
 * 2) 内存态中所有非空且非 `$$` 引用的明文密钥均不能以裸明文形态出现在磁盘 JSON 中。
 */
internal fun isAlreadyFieldEncryptedProvidersJson(
    existingDiskRaw: String?,
    providers: List<ProviderSetting>,
): Boolean {
    if (existingDiskRaw == null) return false
    val trimmed = existingDiskRaw.trimStart { it.isWhitespace() || it == '\uFEFF' }
    if (!trimmed.startsWith("[")) return false
    return providers.all { provider -> !hasPlaintextSecretOnDisk(trimmed, provider) }
}

private fun hasPlaintextSecretOnDisk(diskJson: String, provider: ProviderSetting): Boolean =
    provider.plainSecretValues().any { plain -> diskJson.contains(JsonInstant.encodeToString(plain)) }

/** 该 provider 内仍以裸明文存在的凭据值（`enc:v1:` 密文与 `$$` 引用都不算）。 */
private fun ProviderSetting.plainSecretValues(): List<String> = buildList {
    fun addIfPlain(v: String) {
        if (v.isNotEmpty() && !v.startsWith(ProviderCredentialCipher.ENC_V1_PREFIX) && !v.startsWith(VAULT_REF_PREFIX)) {
            add(v)
        }
    }
    when (this@plainSecretValues) {
        is ProviderSetting.OpenAI -> addIfPlain(apiKey)
        is ProviderSetting.Google -> {
            addIfPlain(apiKey)
            addIfPlain(privateKey)
        }
        is ProviderSetting.Claude -> addIfPlain(apiKey)
        is ProviderSetting.Backend -> {
            addIfPlain(password)
            addIfPlain(token)
            addIfPlain(webBridgePassword)
        }
        else -> Unit
    }
    customHeaders.forEach { addIfPlain(it.value) }
    models.forEach { m -> m.customHeaders.forEach { addIfPlain(it.value) } }
}

/**
 * 写盘前扫描磁盘存量 JSON 中无法解开的 `enc:v1:` 密文并登记到保护表，
 * 确保即使上层尝试写入空串也不会覆盖尚未恢复的坏密文。
 */
private fun captureDegradedSlotsFromDisk(existingDiskRaw: String?) {
    if (existingDiskRaw.isNullOrBlank()) return
    val trimmed = existingDiskRaw.trimStart { it.isWhitespace() || it == '\uFEFF' }
    if (!trimmed.startsWith("[")) return
    runCatching {
        val array = JsonInstant.parseToJsonElement(trimmed) as? JsonArray ?: return
        array.forEach { element ->
            val rawProvider = JsonInstant.decodeFromJsonElement<ProviderSetting>(element)
            recordUndecryptableSlotsOf(rawProvider, rawProvider.id.toString())
        }
    }
}

private fun recordUndecryptableSlotsOf(provider: ProviderSetting, ownerId: String) {
    fun checkSlot(fieldPath: String, raw: String) {
        if (raw.startsWith(ProviderCredentialCipher.ENC_V1_PREFIX) &&
            ProviderCredentialCipher.decrypt(raw.removePrefix(ProviderCredentialCipher.ENC_V1_PREFIX)) == null
        ) {
            ProviderCredentialCipher.recordDegradedSlot(ownerId, fieldPath, raw)
        }
    }
    when (provider) {
        is ProviderSetting.OpenAI -> checkSlot("apiKey", provider.apiKey)
        is ProviderSetting.Google -> {
            checkSlot("apiKey", provider.apiKey)
            checkSlot("privateKey", provider.privateKey)
        }
        is ProviderSetting.Claude -> checkSlot("apiKey", provider.apiKey)
        is ProviderSetting.Backend -> {
            checkSlot("password", provider.password)
            checkSlot("token", provider.token)
            checkSlot("webBridgePassword", provider.webBridgePassword)
        }
        else -> Unit
    }
    provider.customHeaders.forEachIndexed { idx, h ->
        checkSlot("customHeaders[$idx:${h.name}]", h.value)
    }
    provider.models.forEach { m ->
        m.customHeaders.forEachIndexed { idx, h ->
            checkSlot("model(${m.id}).customHeaders[$idx:${h.name}]", h.value)
        }
        m.providerOverwrite?.let { recordUndecryptableSlotsOf(it, "$ownerId/model(${m.id})") }
    }
}

internal fun ProviderSetting.withEncryptedSecrets(ownerIdOverride: String? = null): ProviderSetting {
    val ownerId = ownerIdOverride ?: id.toString()
    val encHeaders = customHeaders.mapIndexed { idx, h ->
        h.copy(value = ProviderCredentialCipher.encryptField(h.value, ownerId, "customHeaders[$idx:${h.name}]"))
    }
    val encModels = models.map { it.withEncryptedSecrets(ownerId) }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(
            apiKey = ProviderCredentialCipher.encryptField(apiKey, ownerId, "apiKey"),
            customHeaders = encHeaders,
            models = encModels,
        )
        is ProviderSetting.Google -> copy(
            apiKey = ProviderCredentialCipher.encryptField(apiKey, ownerId, "apiKey"),
            privateKey = ProviderCredentialCipher.encryptField(privateKey, ownerId, "privateKey"),
            customHeaders = encHeaders,
            models = encModels,
        )
        is ProviderSetting.Claude -> copy(
            apiKey = ProviderCredentialCipher.encryptField(apiKey, ownerId, "apiKey"),
            customHeaders = encHeaders,
            models = encModels,
        )
        is ProviderSetting.Backend -> copy(
            password = ProviderCredentialCipher.encryptField(password, ownerId, "password"),
            token = ProviderCredentialCipher.encryptField(token, ownerId, "token"),
            webBridgePassword = ProviderCredentialCipher.encryptField(webBridgePassword, ownerId, "webBridgePassword"),
            customHeaders = encHeaders,
            models = encModels,
        )
        is ProviderSetting.AICore -> copy(customHeaders = encHeaders, models = encModels)
        is ProviderSetting.LiteRtLocal -> copy(customHeaders = encHeaders, models = encModels)
        is ProviderSetting.LlamaCppLocal -> copy(customHeaders = encHeaders, models = encModels)
        is ProviderSetting.Codex -> copy(customHeaders = encHeaders, models = encModels)
        is ProviderSetting.Grok -> copy(customHeaders = encHeaders, models = encModels)
        is ProviderSetting.GeminiOAuth -> copy(customHeaders = encHeaders, models = encModels)
    }
}

internal fun ProviderSetting.withDecryptedSecrets(ownerIdOverride: String? = null): ProviderSetting {
    val ownerId = ownerIdOverride ?: id.toString()
    val decHeaders = customHeaders.mapIndexed { idx, h ->
        h.copy(value = ProviderCredentialCipher.decryptField(h.value, ownerId, "customHeaders[$idx:${h.name}]"))
    }
    val decModels = models.map { it.withDecryptedSecrets(ownerId) }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(
            apiKey = ProviderCredentialCipher.decryptField(apiKey, ownerId, "apiKey"),
            customHeaders = decHeaders,
            models = decModels,
        )
        is ProviderSetting.Google -> copy(
            apiKey = ProviderCredentialCipher.decryptField(apiKey, ownerId, "apiKey"),
            privateKey = ProviderCredentialCipher.decryptField(privateKey, ownerId, "privateKey"),
            customHeaders = decHeaders,
            models = decModels,
        )
        is ProviderSetting.Claude -> copy(
            apiKey = ProviderCredentialCipher.decryptField(apiKey, ownerId, "apiKey"),
            customHeaders = decHeaders,
            models = decModels,
        )
        is ProviderSetting.Backend -> copy(
            password = ProviderCredentialCipher.decryptField(password, ownerId, "password"),
            token = ProviderCredentialCipher.decryptField(token, ownerId, "token"),
            webBridgePassword = ProviderCredentialCipher.decryptField(webBridgePassword, ownerId, "webBridgePassword"),
            customHeaders = decHeaders,
            models = decModels,
        )
        is ProviderSetting.AICore -> copy(customHeaders = decHeaders, models = decModels)
        is ProviderSetting.LiteRtLocal -> copy(customHeaders = decHeaders, models = decModels)
        is ProviderSetting.LlamaCppLocal -> copy(customHeaders = decHeaders, models = decModels)
        is ProviderSetting.Codex -> copy(customHeaders = decHeaders, models = decModels)
        is ProviderSetting.Grok -> copy(customHeaders = decHeaders, models = decModels)
        is ProviderSetting.GeminiOAuth -> copy(customHeaders = decHeaders, models = decModels)
    }
}

private fun Model.withEncryptedSecrets(ownerId: String): Model = copy(
    customHeaders = customHeaders.mapIndexed { idx, h ->
        h.copy(
            value = ProviderCredentialCipher.encryptField(
                h.value,
                ownerId,
                "model($id).customHeaders[$idx:${h.name}]",
            ),
        )
    },
    providerOverwrite = providerOverwrite?.withEncryptedSecrets("$ownerId/model($id)"),
)

private fun Model.withDecryptedSecrets(ownerId: String): Model = copy(
    customHeaders = customHeaders.mapIndexed { idx, h ->
        h.copy(
            value = ProviderCredentialCipher.decryptField(
                h.value,
                ownerId,
                "model($id).customHeaders[$idx:${h.name}]",
            ),
        )
    },
    providerOverwrite = providerOverwrite?.withDecryptedSecrets("$ownerId/model($id)"),
)

/**
 * 备份导出脱敏（用于 `settings.json` 常规备份与云同步）：
 * - 非敏感配置（`id`、`name`、`baseUrl`、`models[]`、`balanceOption`、`routing` 等）完整保留；
 * - `$$凭证名` Vault 引用完整保留（引用名不含明文密钥）；
 * - 明文密钥与本机绑定的 `enc:v1:` 密文统一脱敏为空串 `""`，防止明文密钥随 `settings.json` 泄露，
 *   同时保证新装导入时非敏感配置 100% 恢复（跨机密钥迁移由口令加密的 `migration/providers.json` 负责）。
 */
internal fun withRedactedProviderSecretsForBackup(settings: Settings): Settings =
    settings.copy(providers = settings.providers.map { it.withRedactedSecretsForBackup() })

internal fun ProviderSetting.withRedactedSecretsForBackup(): ProviderSetting {
    fun redact(v: String): String = if (v.startsWith(VAULT_REF_PREFIX)) v else ""
    val cleanHeaders = customHeaders.map { h -> h.copy(value = redact(h.value)) }
    val cleanModels = models.map { m ->
        m.copy(
            customHeaders = m.customHeaders.map { h -> h.copy(value = redact(h.value)) },
            providerOverwrite = m.providerOverwrite?.withRedactedSecretsForBackup(),
        )
    }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(apiKey = redact(apiKey), customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Google -> copy(
            apiKey = redact(apiKey),
            privateKey = redact(privateKey),
            customHeaders = cleanHeaders,
            models = cleanModels,
        )
        is ProviderSetting.Claude -> copy(apiKey = redact(apiKey), customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Backend -> copy(
            password = redact(password),
            token = redact(token),
            webBridgePassword = redact(webBridgePassword),
            customHeaders = cleanHeaders,
            models = cleanModels,
        )
        is ProviderSetting.AICore -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.LiteRtLocal -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.LlamaCppLocal -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Codex -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Grok -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.GeminiOAuth -> copy(customHeaders = cleanHeaders, models = cleanModels)
    }
}

/**
 * 备份导入还原净化（用于从 `settings.json` 恢复到新装设备时）：
 * - 若条目中带有 `enc:v1:` 密文：能用本机密钥解开则还原明文；若新装设备无原密钥导致解不开，
 *   则清空该不可跨机密文为 `""` 并打印明确可见的恢复提示，保证非敏感配置全量恢复且不被外源坏密文锁死。
 */
internal fun withSanitizedProvidersFromBackup(settings: Settings): Settings {
    val missingKeyProviders = mutableListOf<String>()
    val sanitized = settings.providers.map { p ->
        val cleaned = p.sanitizeSecretFieldsFromBackup()
        if (cleaned.isEnabledRemoteProviderMissingKey()) {
            missingKeyProviders += cleaned.name.ifBlank { cleaned.id.toString().take(8) }
        }
        cleaned
    }
    if (missingKeyProviders.isNotEmpty()) {
        AppLog.w(
            TAG_PROVIDER_CRYPTO,
            "备份恢复完成：提供商 ${missingKeyProviders.joinToString(", ")} 的非敏感配置与模型列表已恢复，但密钥为空（请在设置 → 提供商中补填密钥）",
        )
    }
    return settings.copy(providers = sanitized)
}

private fun ProviderSetting.sanitizeSecretFieldsFromBackup(): ProviderSetting {
    fun sanitize(v: String): String =
        if (!v.startsWith(ProviderCredentialCipher.ENC_V1_PREFIX)) {
            v
        } else {
            ProviderCredentialCipher.decrypt(v.removePrefix(ProviderCredentialCipher.ENC_V1_PREFIX)).orEmpty()
        }
    val cleanHeaders = customHeaders.map { h -> CustomHeader(h.name, sanitize(h.value)) }
    val cleanModels = models.map { m ->
        m.copy(
            customHeaders = m.customHeaders.map { h -> CustomHeader(h.name, sanitize(h.value)) },
            providerOverwrite = m.providerOverwrite?.sanitizeSecretFieldsFromBackup(),
        )
    }
    return when (this) {
        is ProviderSetting.OpenAI -> copy(apiKey = sanitize(apiKey), customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Google -> copy(
            apiKey = sanitize(apiKey),
            privateKey = sanitize(privateKey),
            customHeaders = cleanHeaders,
            models = cleanModels,
        )
        is ProviderSetting.Claude -> copy(apiKey = sanitize(apiKey), customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Backend -> copy(
            password = sanitize(password),
            token = sanitize(token),
            webBridgePassword = sanitize(webBridgePassword),
            customHeaders = cleanHeaders,
            models = cleanModels,
        )
        is ProviderSetting.AICore -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.LiteRtLocal -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.LlamaCppLocal -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Codex -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.Grok -> copy(customHeaders = cleanHeaders, models = cleanModels)
        is ProviderSetting.GeminiOAuth -> copy(customHeaders = cleanHeaders, models = cleanModels)
    }
}

private fun ProviderSetting.isEnabledRemoteProviderMissingKey(): Boolean {
    if (!enabled) return false
    return when (this) {
        is ProviderSetting.OpenAI -> apiKey.isBlank() && customHeaders.none { it.value.isNotBlank() }
        is ProviderSetting.Google ->
            if (vertexAI && useServiceAccount) privateKey.isBlank() else apiKey.isBlank()
        is ProviderSetting.Claude -> apiKey.isBlank() && customHeaders.none { it.value.isNotBlank() }
        is ProviderSetting.Backend ->
            backendType != "cli" && password.isBlank() && token.isBlank() && customHeaders.none { it.value.isNotBlank() }
        else -> false
    }
}
