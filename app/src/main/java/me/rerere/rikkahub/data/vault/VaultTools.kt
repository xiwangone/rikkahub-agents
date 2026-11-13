package me.rerere.rikkahub.data.vault

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Vault 凭证工具（App 内 AI 凭证中枢）。
 *
 * 安全模型：凭证值只在 App 进程内存使用（JSch 字节加载 / 密码对象），
 * 工具返回值不含明文——只返回变量名、公钥、命令输出。
 */
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 列出凭证库条目（不含值）。 */
/**
 * 列出「值本身是公钥」的凭证条目（公钥不该单列）及其可并入的宿主。
 * 只读：不返回值、不改配置。
 */
fun vaultPublicKeyEntriesTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_public_key_entries",
    description =
        "List vault entries whose **value is itself an SSH public key** — public keys belong to a " +
            "private-key entry's `publicKey` field, not as standalone entries. Read-only: reports the " +
            "entry name, its group, and the host entry it could merge into (matched by fingerprint " +
            "first, then by name suffix like _PUB/.PUB). Use before cleaning up, e.g. export via " +
            "vault_export_loadcreds, move the public key into the host entry's SSH公钥 comment, delete " +
            "the standalone entry, then vault_import_loadcreds.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()) },
    execute = { _ ->
        val rows = repository.findPublicKeyOnlyEntries()
        if (rows.isEmpty()) {
            listOf(UIMessagePart.Text("✅ No standalone public key entries found (public keys all live on private-key entries)"))
        } else {
            val body = buildString {
                appendLine("Found ${rows.size} standalone public key entries (public keys do not need their own entries):")
                rows.forEach { r ->
                    appendLine("- ${r.entry.name} (${r.entry.grp}) → can merge into: ${r.hostName ?: "no host matched (needs manual confirmation)"}")
                }
                append("Fix: export with vault_export_loadcreds → write the public key into the host entry's `# SSH公钥:` comment and delete that entry → import with vault_import_loadcreds.")
            }
            listOf(UIMessagePart.Text(body))
        }
    },
)

@Suppress("CyclomaticComplexMethod") // 参数说明与可选分支平铺展示，拆开反而难对照
fun vaultCredentialNamesTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_names",
    description =
        "List credentials stored in the vault (names only, never values). " +
            "Supports optional keyword search (matches name / description, case-insensitive), " +
            "optional group filter, and sorting. SSH key entries show their public-key SHA256 fingerprint. " +
            "Pass duplicates=true to also report suspicious same-group-same-length duplicates (read-only hint). " +
            "Results are sorted by group then name by default. " +
            "Use to discover which credential names are available before calling vault_ssh_exec / vault_http_exec.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("group", buildJsonObject { put("type", "string"); put("description", "Filter by group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("keyword", buildJsonObject { put("type", "string"); put("description", "Optional search keyword (matches name or description, case-insensitive)") })
                    put("sort", buildJsonObject { put("type", "string"); put("description", "Sort order: name / group / length (default group-then-name)") })
                    put("duplicates", buildJsonObject { put("type", "boolean"); put("description", "When true, also report same-value entries (exact match by value fingerprint; plaintext is never compared) as a read-only hint") })
                },
        )
    },
    execute = { params ->
        // 存量条目类型回填（幂等、便宜）：保证 AI 首次调用就能读到类型，而不是等用户逐条重存
        runCatching { repository.backfillMissingTypes() }
        val group = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull
        val keyword = params.jsonObject["keyword"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val sort = params.jsonObject["sort"]?.jsonPrimitive?.contentOrNull
        val showDupes = params.jsonObject["duplicates"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val all = repository.getAll()
        val filtered =
            all
                .filter { e -> group == null || e.grp == group }
                .filter { e -> keyword == null || e.name.lowercase().contains(keyword) || e.description.lowercase().contains(keyword) }
        val entries =
            when (sort) {
                "name" -> filtered.sortedBy { it.name.lowercase() }
                "group" -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
                "length" -> filtered.sortedBy { it.valueLength }
                else -> filtered.sortedWith(compareBy({ it.grp }, { it.name.lowercase() }))
            }
        val sb = StringBuilder()
        if (showDupes) {
            // 精确判重：按值指纹（同指纹 = 同值；指纹随保存写进 meta，无指纹的旧条目不计入）
            val dupGroups =
                all.mapNotNull { e -> CredentialMeta.decode(e.metaJson)["value_fp"]?.let { it to e.name } }
                    .groupBy { it.first }
                    .filter { (_, items) -> items.size > 1 }
            val withoutFp = all.count { CredentialMeta.decode(it.metaJson)["value_fp"].isNullOrBlank() }
            if (dupGroups.isEmpty()) {
                sb.append("(no entries with the same value)\n")
            } else {
                sb.append("⚠️ Entries with the same value (matched exactly by value fingerprint; within one group they are the same secret):\n")
                dupGroups.forEach { (fp, items) ->
                    sb.append("  fp=${fp.take(8)}: ${items.joinToString(" / ") { it.second }}\n")
                }
                sb.append("Merge them with vault_credential_merge (config references are rewritten together).\n")
            }
            if (withoutFp > 0) {
                sb.append("ℹ️ Another $withoutFp entries have no fingerprint yet (generated on save/re-save) and were excluded from dedup.\n")
            }
            sb.append("\n")
        }
        if (entries.isEmpty()) {
            sb.append("(the vault is empty, or no entries match)")
        } else {
            sb.append("Vault entries (${entries.size}):\n")
            entries.forEach { e ->
                val fp = if (e.publicKey.isNotBlank()) " fp=${SshKeyGenerator.fingerprint(e.publicKey) ?: "?"}" else ""
                val tt = if (e.type.isNotBlank()) " <${e.type}>" else ""
                val em = CredentialMeta.decode(e.metaJson)
                val vfp = em["value_fp"]?.take(8)?.let { " vfp=$it" } ?: ""
                val metaBrief =
                    em.filterKeys { it != "value_fp" }.entries
                        .joinToString(", ") { (k, v) -> "$k=$v" }
                        .let { if (it.isBlank()) "" else " {$it}" }
                sb.append("${e.name}  [${e.grp}]$tt len=${e.valueLength}$fp$vfp$metaBrief  ${e.description}\n")
                // 体检结论：只落 ERROR（WARN 是"写法非标准但可用"，逐条显示会刷屏）。
                // 列表侧不解密条目值 → 私钥传 null，只覆盖公钥侧的形态/结构问题；
                // 文本只取 Code 名，不拼 Issue.detail（那是界面文案，工具输出保持中性可机读）。
                val healthCodes =
                    VaultKeyHealth.check(e.publicKey, null)
                        .filter { it.severity == VaultKeyHealth.Severity.ERROR }
                        .map { it.code.name }
                        .distinct()
                if (healthCodes.isNotEmpty()) {
                    sb.append("  ! health=ERROR:${healthCodes.joinToString(",")}\n")
                }
            }
        }
        listOf(UIMessagePart.Text(sb.toString().trimEnd()))
    },
)

/** AI 创建凭证条目占位（不含值），由用户在密钥库最后填写 key/token。 */
fun vaultCredentialPrepareTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_credential_prepare",
    description =
        "Create a credential entry placeholder in the vault (name / description / group, NO value). " +
            "The user fills the actual secret later in the vault UI. Use to prepare a named slot " +
            "before the user provides the key/token — the AI never handles the secret value.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name, e.g. NEW_SERVER_API_KEY") })
                    put("description", buildJsonObject { put("type", "string"); put("description", "What this credential is for") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Vault group: Git/AI/ECS/MCP/Notification/SSH/Other") })
                    put("public_key", buildJsonObject { put("type", "string"); put("description", "Optional SSH public key line (plaintext, for SSH key entries; helps AI identify the key later)") })
                    put("meta", buildJsonObject {
                        put("type", "object")
                        put(
                            "description",
                            "Optional NON-sensitive metadata (stored plaintext). Allowed keys: account / access_key_id / user_id / domain_id / project_id / region / endpoint / path / header / prefix / username / algorithm / digits / period. " +
                                "Example: {\"account\":\"me@example.com\",\"access_key_id\":\"AKIA...\",\"region\":\"cn-hangzhou\"}. " +
                                "NEVER put secrets here — the secret value itself is filled by the user.",
                        )
                    })
                },
            required = listOf("name"),
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val rawName = o["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val finalName = rawName?.let { CredentialVaultRepository.normalizeName(it) }.orEmpty()
        when {
            rawName == null -> listOf(UIMessagePart.Text("❌ name is required"))
            !CredentialVaultRepository.validateCredentialName(finalName) ->
                listOf(UIMessagePart.Text("❌ Credential name is invalid (must be UPPER_SNAKE_CASE, max 64 chars): $rawName"))
            repository.getByName(finalName) != null ->
                listOf(UIMessagePart.Text("❌ Credential already exists: $finalName (placeholder creation refused to avoid overwriting existing credential metadata and public key; use vault_credential_update to change metadata)"))
            else -> {
                val desc = o["description"]?.jsonPrimitive?.contentOrNull ?: ""
                val group = o["group"]?.jsonPrimitive?.contentOrNull ?: "Other"
                val pub = o["public_key"]?.jsonPrimitive?.contentOrNull ?: ""
                // meta：非敏感元数据（白名单见 CredentialMeta）—— AI 可在建占位时就写好账号/AK/region 等正位字段
                val metaJson =
                    o["meta"]?.let { meta ->
                        runCatching { meta.jsonObject }.getOrNull()?.let { obj ->
                            CredentialMeta.encode(
                                obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { c -> k to c } }.toMap(),
                            )
                        }
                    } ?: ""
                repository.save(
                    name = finalName,
                    value = "", // 占位：值留空，用户稍后填写
                    description = desc,
                    group = group,
                    publicKey = pub,
                    metaJson = metaJson,
                )
                repository.logAccess(finalName, "ai-tool", "prepare")
                listOf(
                    UIMessagePart.Text(
                        "✅ Created credential placeholder entry: $finalName [${group}]\n" +
                            "Value not filled in yet. Ask the user to open Credential Vault → $finalName and edit it, then fill in the actual key/token." +
                            if (pub.isNotBlank()) "\nPublic key recorded (AI can identify this key from it)." else "",
                    ),
                )
            }
        }
    },
)

/** 未指定 name 时的默认凭证名（按类型区分，避免 WireGuard/age 密钥落成 SSH 名字）。 */
private fun defaultVaultKeyName(type: VaultKeyType): String = when (type) {
    VaultKeyType.WIREGUARD -> "WIREGUARD_KEY"
    VaultKeyType.AGE -> "AGE_KEY"
    VaultKeyType.OPENPGP -> "GPG_KEY"
    VaultKeyType.X509_CERT -> "CLIENT_CERT"
    VaultKeyType.X509_CSR -> "CSR_KEY"
    else -> "WEB_SSH_KEY"
}

/** 未指定 group 时的默认分组。 */
private fun defaultVaultKeyGroup(type: VaultKeyType): String = when (type) {
    VaultKeyType.WIREGUARD, VaultKeyType.AGE, VaultKeyType.OPENPGP -> "Other"
    VaultKeyType.X509_CERT, VaultKeyType.X509_CSR -> "Other"
    else -> "SSH"
}

/** 公钥该往哪儿配 —— 按类型给出提示（SSH 与 WireGuard/age 的用法完全不同）。 */
private fun vaultKeyUsageHint(type: VaultKeyType): String = when (type) {
    VaultKeyType.WIREGUARD -> "Configure this public key on the peer WireGuard (peer's PublicKey):"
    VaultKeyType.AGE -> "Use this public key for age encryption (-r / recipient):"
    VaultKeyType.OPENPGP -> "Upload this public key to your code hosting platform (GitHub account settings → SSH and GPG keys) for commit signing:"
    VaultKeyType.X509_CERT -> "The self-signed certificate can be imported directly into the client/server trust store; the private key is stored in the vault for mTLS client authentication:"
    VaultKeyType.X509_CSR -> "Send the following CSR to a CA/PKI for signing; the private key stays in the vault, and it can be used for mTLS once issued:"
    else -> "Configure the following public key on the server in ~/.ssh/authorized_keys:"
}

/** 把工具参数里的 type 字符串映射到密钥类型；未知值返回 null（由调用方报错）。 */
private fun parseVaultKeyType(raw: String?): VaultKeyType? = when (raw?.uppercase()) {
    "ED25519" -> VaultKeyType.ED25519
    "ECDSA", "ECDSA256" -> VaultKeyType.ECDSA256
    "ECDSA384" -> VaultKeyType.ECDSA384
    "ECDSA521" -> VaultKeyType.ECDSA521
    "RSA", "RSA2048", null -> VaultKeyType.RSA2048
    "RSA4096" -> VaultKeyType.RSA4096
    "WIREGUARD" -> VaultKeyType.WIREGUARD
    "AGE" -> VaultKeyType.AGE
    "OPENPGP", "PGP", "GPG" -> VaultKeyType.OPENPGP
    "X509", "X509CERT", "CERT" -> VaultKeyType.X509_CERT
    "CSR", "PKCS10", "X509CSR" -> VaultKeyType.X509_CSR
    else -> null
}

/** 生成密钥对并保存到凭证库，返回公钥（SSH 类可配置到服务器 authorized_keys）。 */
fun vaultGenKeyTool(
    context: android.content.Context,
    repository: CredentialVaultRepository,
): Tool = Tool(
    name = "vault_gen_key",
    description =
        "Generate an SSH key pair, store the private key in the vault, and return the public key " +
            "for the user to configure on a server (e.g. ~/.ssh/authorized_keys). " +
            "Use before vault_ssh_exec when the server is new and has no key yet. " +
            "Other key types (RSA4096 / ECDSA384 / ECDSA521 / WIREGUARD / AGE / OPENPGP / X509CERT / CSR) are supported too. " +
            "For OPENPGP pass uid ('Name <email>'); for X.509 pass uid as the subject ('CN=host, O=Org')." +
            "The public key line carries a comment identifying the purpose and RikkaHub Agents as generator.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("name", buildJsonObject { put("type", "string"); put("description", "Credential name (default WEB_SSH_KEY)") })
                    put("group", buildJsonObject { put("type", "string"); put("description", "Vault group (default SSH)") })
                    put("comment", buildJsonObject { put("type", "string"); put("description", "Public key comment suffix, e.g. 'pc-main@Yao'. Default 'generated@Yao'. Always include purpose + @Yao for traceability.") })
                    put("type", buildJsonObject { put("type", "string"); put("description", "Key type: ED25519 (recommended) / RSA / RSA4096 / ECDSA / ECDSA384 / ECDSA521 / WIREGUARD / AGE / OPENPGP (default RSA for backward compat)") })
                    put("uid", buildJsonObject { put("type", "string"); put("description", "Identity for OPENPGP ('Name <email>') or subject for X.509 ('CN=host, O=Org'; a bare name is treated as CN). Defaults per type.") })
                },
        )
    },
    needsApproval = { true },
    execute = { params ->
        if (!VaultSessionManager(context).hasActiveAuthorization()) {
            return@Tool listOf(UIMessagePart.Text("❌ Not authorized: complete Vault authorization first (30 minutes or always valid), then call this tool"))
        }
        val rawName = params.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val rawGroup = params.jsonObject["group"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val rawComment = params.jsonObject["comment"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val typeStr = params.jsonObject["type"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val uidStr = params.jsonObject["uid"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val keyType = parseVaultKeyType(typeStr)
            ?: return@Tool listOf(
                UIMessagePart.Text(
                    "❌ Unsupported type: $typeStr" +
                        "(one of ED25519 / RSA / RSA4096 / ECDSA / ECDSA384 / ECDSA521 / WIREGUARD / AGE / OPENPGP / X509CERT / CSR)",
                ),
            )
        // 默认名与分组按类型区分，避免 WireGuard/age 密钥落成 SSH 名字
        val name = rawName ?: defaultVaultKeyName(keyType)
        val group = rawGroup ?: defaultVaultKeyGroup(keyType)
        // 名字统一规范化并校验，且禁止静默覆盖已存在的同名密钥
        val finalName = CredentialVaultRepository.normalizeName(name)
        if (!CredentialVaultRepository.validateCredentialName(finalName)) {
            return@Tool listOf(UIMessagePart.Text("❌ Credential name is invalid (must be UPPER_SNAKE_CASE, max 64 chars): $name"))
        }
        if (repository.getByName(finalName) != null) {
            return@Tool listOf(UIMessagePart.Text("❌ Credential name already exists: $finalName (specify a new name, or explicitly delete the old entry first, to avoid silently overwriting the existing private key)"))
        }
        // 强制注释带 RikkaHub Agents 标识便于溯源；未指定时默认用途注释
        val comment =
            when {
                rawComment == null -> SshKeyGenerator.DEFAULT_COMMENT
                "@Yao" in rawComment || "@rikkahub-agents" in rawComment || "@rikkahub" in rawComment -> rawComment
                else -> "$rawComment@Yao"
            }
        val key = VaultKeyGenerator.generate(keyType, comment, uidStr ?: "")
        val fp = VaultKeyGenerator.fingerprintOf(key.publicText)
        repository.save(
            name = finalName,
            value = key.privateText,
            // 与界面路径的描述保持一致，并带上 RikkaHub Agents 标识便于溯源；
            // 用英文避免硬编码中文（描述会入库并可能被多语言用户看到）
            description = "Key pair (generated by RikkaHub Agents, ${keyType.label}, "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US).format(LocalDate.now()) + ")",
            group = group,
            publicKey = key.publicText,
        )
        repository.logAccess(finalName, "ai-tool", "gen_key")
        listOf(
            UIMessagePart.Text(
                "✅ Key pair generated and saved to the vault ($finalName)\n" +
                    "Type: ${keyType.label}" + (fp?.let { " | Fingerprint: $it" } ?: "") + "\n" +
                    "The private key was stored in the Vault in app memory (no plaintext written to disk); the public key is stored in the same entry's publicKey field (plaintext)\n" +
                    vaultKeyUsageHint(keyType) + "\n${key.publicText}",
            ),
        )
    },
)
