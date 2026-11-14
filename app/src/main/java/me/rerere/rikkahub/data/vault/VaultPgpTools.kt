package me.rerere.rikkahub.data.vault

import me.rerere.common.http.optString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuard

/**
 * Vault 凭证工具（App 内 AI 凭证中枢）。
 *
 * 安全模型：
 * - `vault_pgp_decrypt`：用私钥解密密文是工具的既定用途，解密出的明文直接返回给 AI；
 *   私钥本身只在 App 进程内存中使用，不落盘、不返回。
 * - 其余三工具只在 App 进程内存使用凭证值，工具返回值不含明文：sign 只返回 detached
 *   签名、verify 只返回校验结果、encrypt 只返回 armored 密文。
 */

// ================= 档位 3b：GPG 操作（sign / verify / encrypt / decrypt） =================

/** GPG 工具的 key 参数解析结果：私钥文本 / 公钥文本（走条目引用时两者都可能有）。 */
private class PgpKeyMaterial(val privateKey: String?, val publicKey: String?, val entryName: String?)

private sealed interface PgpKeyResolution {
    data class Ok(val material: PgpKeyMaterial) : PgpKeyResolution
    data class Err(val message: String) : PgpKeyResolution
}

private fun pgpFail(message: String): List<UIMessagePart> = listOf(UIMessagePart.Text("❌ $message"))

/**
 * 解析 GPG 工具的 `key` 参数：`$$引用`（取条目 value=私钥 + publicKey 字段=公钥）
 * 或直接粘贴的 armored 公钥/私钥块。
 */
private const val MAX_PGP_FILE_BYTES = 16L * 1024 * 1024

private suspend fun resolvePgpKey(
    repository: CredentialVaultRepository,
    raw: String?,
    caller: String,
    context: android.content.Context? = null,
): PgpKeyResolution {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return PgpKeyResolution.Err("key is required (entry reference or armored PGP key block)")
    if (isVaultReference(text)) {
        val name = text.removePrefix(VAULT_REF_PREFIX).trim()
        val authCheck: (suspend (String) -> Boolean)? = if (context != null) {
            { name -> VaultSessionManager(context).hasActiveAuthorization(name) }
        } else {
            null
        }
        val resolution = CredentialResolver(repository, isAuthorized = authCheck)
            .resolve(name, CredentialPurpose.LOCAL_USE, caller = caller)
        val granted = resolution as? CredentialResolution.Granted
            ?: return PgpKeyResolution.Err("Referenced entry \"$name\" did not resolve to a usable credential (does not exist or not authorized)")
        return PgpKeyResolution.Ok(
            PgpKeyMaterial(
                privateKey = granted.value,
                publicKey = granted.entry.publicKey?.ifBlank { null },
                entryName = granted.entry.name,
            ),
        )
    }
    val isPublic = text.contains("BEGIN PGP PUBLIC KEY BLOCK")
    val isPrivate = text.contains("BEGIN PGP PRIVATE KEY BLOCK")
    if (!isPublic && !isPrivate) {
        return PgpKeyResolution.Err("key is neither an entry reference nor an armored PGP key block")
    }
    return PgpKeyResolution.Ok(
        PgpKeyMaterial(
            privateKey = if (isPrivate) text else null,
            publicKey = text,
            entryName = null,
        ),
    )
}

/** 载荷读取：`data`（文本）与 `file`（路径读入）必填其一，互斥。 */
private fun readPgpPayload(o: kotlinx.serialization.json.JsonObject): Result<ByteArray> {
    val dataRaw = o.optString("data")?.ifBlank { null }
    val fileRaw = o.optString("file")?.ifBlank { null }
    return when {
        dataRaw != null && fileRaw != null ->
            Result.failure(IllegalArgumentException("Only one of data and file may be passed"))
        dataRaw != null ->
            Result.success(dataRaw.toByteArray(Charsets.UTF_8))
        fileRaw != null -> readPayloadFromFile(fileRaw)
        else ->
            Result.failure(IllegalArgumentException("One of data and file is required (the text or file path to process)"))
    }
}

/** 从文件路径读取载荷：路径安全校验 → 大小上限 → 读取。 */
private fun readPayloadFromFile(fileRaw: String): Result<ByteArray> {
    val expanded = AgentWorkspace.expand(fileRaw)
    val violation = PathSafetyGuard.check(expanded)
    if (violation != null) {
        return Result.failure(IllegalArgumentException("File path is restricted ($fileRaw): ${violation.detail}"))
    }
    val file = java.io.File(expanded)
    if (file.length() > MAX_PGP_FILE_BYTES) {
        return Result.failure(IllegalArgumentException("File exceeds the 16MB limit ($fileRaw)"))
    }
    return try {
        Result.success(file.readBytes())
    } catch (e: java.io.IOException) {
        // File.readBytes 仅声明 IOException（FileNotFoundException 为其子类）
        Result.failure(IllegalArgumentException("Failed to read file ($fileRaw): ${e.message}"))
    }
}

private suspend fun resolvePgpPassphrase(
    repository: CredentialVaultRepository,
    o: kotlinx.serialization.json.JsonObject,
    caller: String,
    context: android.content.Context? = null,
): CharArray? = resolveLiteralOrReference(
    o.optString("passphrase"),
    CredentialPurpose.LOCAL_USE,
    repository,
    caller = caller,
    // 口令引用也要过会话授权门（LOCAL_USE 自 df5ad9c71 起必须授权）：无 context 时拒绝（fail-closed）。
    isAuthorized = context?.let { ctx ->
        { name: String -> VaultSessionManager(ctx).hasActiveAuthorization(name) }
    },
)?.toCharArray()

/** vault_pgp_sign —— 对文本/文件生成 GPG detached 签名（armored）。 */
fun vaultPgpSignTool(repository: CredentialVaultRepository, context: android.content.Context? = null): Tool = Tool(
    name = "vault_pgp_sign",
    description =
        "Create an ASCII-armored detached GPG/PGP signature over text or a file, using a private key " +
            "stored in the vault (a '$$\u0024NAME' reference) or pasted armored key text. " +
            "Output is the signature block, checkable with vault_pgp_verify. " +
            "Optional passphrase handles passphrase-protected keys (literal or reference).",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("key", buildJsonObject { put("type", "string"); put("description", "Vault reference (e.g. \$\$GPG_KEY) or armored PGP private key text") })
                    put("data", buildJsonObject { put("type", "string"); put("description", "Text content to sign (mutually exclusive with file)") })
                    put("file", buildJsonObject { put("type", "string"); put("description", "Absolute file path to read and sign (mutually exclusive with data)") })
                    put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional key passphrase: literal text or vault reference") })
                },
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val keyRes = resolvePgpKey(repository, o.optString("key"), caller = "pgp-sign", context = context)
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "Failed to read payload") }
        val privateKey = material.privateKey ?: return@Tool pgpFail("Signing needs a private key (the current key is a public key)")
        val passphrase = resolvePgpPassphrase(repository, o, caller = "pgp-sign", context = context)
        val signature =
            try {
                PgpCrypto.sign(privateKey, payload, passphrase)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("Signing failed: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_sign") }
        listOf(UIMessagePart.Text("✅ GPG signing done (detached armored):\n\n$signature"))
    },
)

/** vault_pgp_verify —— 校验 detached 签名。 */
fun vaultPgpVerifyTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_pgp_verify",
    description =
        "Verify an ASCII-armored detached GPG/PGP signature over text or a file against a public key " +
            "(vault reference, or pasted armored public/private key text). Returns whether the signature " +
            "is valid plus the signer fingerprint.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("key", buildJsonObject { put("type", "string"); put("description", "Vault reference or armored PGP public key text (private key text also accepted)") })
                    put("data", buildJsonObject { put("type", "string"); put("description", "Signed text content (mutually exclusive with file)") })
                    put("file", buildJsonObject { put("type", "string"); put("description", "Absolute file path of the signed file (mutually exclusive with data)") })
                    put("signature", buildJsonObject { put("type", "string"); put("description", "Detached signature text, ASCII-armored (mutually exclusive with signature_file)") })
                    put("signature_file", buildJsonObject { put("type", "string"); put("description", "Absolute path of a detached signature file (armored or binary; mutually exclusive with signature)") })
                },
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val keyRes = resolvePgpKey(repository, o.optString("key"), caller = "pgp-verify")
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "Failed to read payload") }
        val signatureRaw = o.optString("signature")?.ifBlank { null }
        val signatureFile = o.optString("signature_file")?.ifBlank { null }
        val signature: ByteArray =
            when {
                signatureRaw != null && signatureFile != null ->
                    return@Tool pgpFail("Only one of signature and signature_file may be passed")
                signatureRaw != null -> signatureRaw.toByteArray(Charsets.UTF_8)
                signatureFile != null -> {
                    val expandedSig = AgentWorkspace.expand(signatureFile)
                    PathSafetyGuard.check(expandedSig)?.let { v ->
                        return@Tool pgpFail("Signature file path is restricted ($signatureFile): ${v.detail}")
                    }
                    runCatching { java.io.File(expandedSig).readBytes() }
                        .getOrElse { return@Tool pgpFail("Failed to read signature file ($signatureFile): ${it.message}") }
                }
                else -> return@Tool pgpFail("One of signature and signature_file is required")
            }
        val verifierKey = material.publicKey ?: material.privateKey
            ?: return@Tool pgpFail("key did not resolve to a usable key")
        when (val result = PgpCrypto.verify(verifierKey, payload, signature)) {
            is PgpCrypto.VerifyResult.Valid ->
                listOf(UIMessagePart.Text("✅ Signature verified (signer fingerprint ${result.fingerprint})"))
            is PgpCrypto.VerifyResult.Invalid ->
                listOf(UIMessagePart.Text("❌ Signature verification failed: ${result.reason}"))
        }
    },
)

/** vault_pgp_encrypt —— 用公钥加密文本/文件（AES-256，armored 密文）。 */
fun vaultPgpEncryptTool(repository: CredentialVaultRepository): Tool = Tool(
    name = "vault_pgp_encrypt",
    description =
        "Encrypt text or a file for a recipient's GPG public key (vault reference with a publicKey " +
            "field, or pasted armored key text that contains an ECDH encryption subkey). " +
            "Returns ASCII-armored ciphertext; decryptable by the recipient with gpg --decrypt or " +
            "vault_pgp_decrypt.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("key", buildJsonObject { put("type", "string"); put("description", "Recipient vault reference or armored PGP public key text") })
                    put("data", buildJsonObject { put("type", "string"); put("description", "Plaintext to encrypt (mutually exclusive with file)") })
                    put("file", buildJsonObject { put("type", "string"); put("description", "Absolute file path to read and encrypt (mutually exclusive with data)") })
                },
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val keyRes = resolvePgpKey(repository, o.optString("key"), caller = "pgp-encrypt")
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "Failed to read payload") }
        val recipientKey = material.publicKey ?: material.privateKey
            ?: return@Tool pgpFail("key did not resolve to a usable public key")
        val ciphertext =
            try {
                PgpCrypto.encrypt(recipientKey, payload)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("Encryption failed: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_encrypt") }
        listOf(UIMessagePart.Text("✅ GPG encryption done (armored ciphertext):\n\n$ciphertext"))
    },
)

/** vault_pgp_decrypt —— 用私钥解密 armored/二进制密文。 */
fun vaultPgpDecryptTool(repository: CredentialVaultRepository, context: android.content.Context? = null): Tool = Tool(
    name = "vault_pgp_decrypt",
    description =
        "Decrypt GPG/PGP message text or a file with a private key from the vault (reference or " +
            "armored key text). Accepts ASCII-armored or binary ciphertext. Optional passphrase for " +
            "passphrase-protected keys (literal or reference). Returns the plaintext; text payloads " +
            "only - use generic file tools to persist binary output.",
    parameters = {
        InputSchema.Obj(
            properties =
                buildJsonObject {
                    put("key", buildJsonObject { put("type", "string"); put("description", "Vault reference (e.g. \$\$GPG_KEY) or armored PGP private key text") })
                    put("data", buildJsonObject { put("type", "string"); put("description", "Ciphertext content: armored PGP message (mutually exclusive with file)") })
                    put("file", buildJsonObject { put("type", "string"); put("description", "Absolute path of the ciphertext file, armored or binary (mutually exclusive with data)") })
                    put("passphrase", buildJsonObject { put("type", "string"); put("description", "Optional key passphrase: literal text or vault reference") })
                },
        )
    },
    execute = { params ->
        val o = params.jsonObject
        val keyRes = resolvePgpKey(repository, o.optString("key"), caller = "pgp-decrypt", context = context)
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val ciphertext = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "Failed to read payload") }
        val privateKey = material.privateKey ?: return@Tool pgpFail("Decryption needs a private key (the current key is a public key)")
        val passphrase = resolvePgpPassphrase(repository, o, caller = "pgp-decrypt", context = context)
        val plaintext =
            try {
                PgpCrypto.decrypt(privateKey, ciphertext, passphrase)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("Decryption failed: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_decrypt") }
        listOf(UIMessagePart.Text("✅ GPG decryption result:\n${plaintext.toString(Charsets.UTF_8)}"))
    },
)
