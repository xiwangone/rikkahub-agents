package me.rerere.rikkahub.data.vault

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import me.rerere.rikkahub.data.ai.tools.local.PathSafetyGuard

/**
 * Vault 凭证工具（App 内 AI 凭证中枢）。
 *
 * 安全模型：凭证值只在 App 进程内存使用（JSch 字节加载 / 密码对象），
 * 工具返回值不含明文——只返回变量名、公钥、命令输出。
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
    if (text.isEmpty()) return PgpKeyResolution.Err("key 必填（条目引用或 armored PGP 密钥块）")
    if (isVaultReference(text)) {
        val name = text.removePrefix(VAULT_REF_PREFIX).trim()
        val authCheck = context?.let { ctx -> { VaultSessionManager(ctx).hasActiveAuthorization() } }
        val resolution = CredentialResolver(repository, isAuthorized = authCheck)
            .resolve(name, CredentialPurpose.LOCAL_USE, caller = caller)
        val granted = resolution as? CredentialResolution.Granted
            ?: return PgpKeyResolution.Err("引用条目「$name」未解析到可用凭证（不存在或未授权）")
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
        return PgpKeyResolution.Err("key 既不是条目引用，也不是 armored PGP 密钥块")
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
    val dataRaw = o["data"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    val fileRaw = o["file"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
    return when {
        dataRaw != null && fileRaw != null ->
            Result.failure(IllegalArgumentException("data 与 file 只能传一个"))
        dataRaw != null ->
            Result.success(dataRaw.toByteArray(Charsets.UTF_8))
        fileRaw != null -> readPayloadFromFile(fileRaw)
        else ->
            Result.failure(IllegalArgumentException("data 与 file 必须传其一（要处理的文本或文件路径）"))
    }
}

/** 从文件路径读取载荷：路径安全校验 → 大小上限 → 读取。 */
private fun readPayloadFromFile(fileRaw: String): Result<ByteArray> {
    val expanded = AgentWorkspace.expand(fileRaw)
    val violation = PathSafetyGuard.check(expanded)
    if (violation != null) {
        return Result.failure(IllegalArgumentException("文件路径受限（$fileRaw）: ${violation.detail}"))
    }
    val file = java.io.File(expanded)
    if (file.length() > MAX_PGP_FILE_BYTES) {
        return Result.failure(IllegalArgumentException("文件超过 16MB 上限（$fileRaw）"))
    }
    return try {
        Result.success(file.readBytes())
    } catch (e: java.io.IOException) {
        // File.readBytes 仅声明 IOException（FileNotFoundException 为其子类）
        Result.failure(IllegalArgumentException("读文件失败（$fileRaw）: ${e.message}"))
    }
}

private suspend fun resolvePgpPassphrase(
    repository: CredentialVaultRepository,
    o: kotlinx.serialization.json.JsonObject,
    caller: String,
    context: android.content.Context? = null,
): CharArray? = resolveLiteralOrReference(
    o["passphrase"]?.jsonPrimitive?.contentOrNull,
    CredentialPurpose.LOCAL_USE,
    repository,
    isAuthorized = context?.let { ctx -> { VaultSessionManager(ctx).hasActiveAuthorization() } },
    caller = caller,
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
        val keyRes = resolvePgpKey(repository, o["key"]?.jsonPrimitive?.contentOrNull, caller = "pgp-sign", context = context)
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "载荷读取失败") }
        val privateKey = material.privateKey ?: return@Tool pgpFail("签名需要私钥（当前 key 是公钥）")
        val passphrase = resolvePgpPassphrase(repository, o, caller = "pgp-sign", context = context)
        val signature =
            try {
                PgpCrypto.sign(privateKey, payload, passphrase)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("签名失败: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_sign") }
        listOf(UIMessagePart.Text("✅ GPG 签名完成（detached armored）：\n\n$signature"))
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
        val keyRes = resolvePgpKey(repository, o["key"]?.jsonPrimitive?.contentOrNull, caller = "pgp-verify")
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "载荷读取失败") }
        val signatureRaw = o["signature"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val signatureFile = o["signature_file"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val signature: ByteArray =
            when {
                signatureRaw != null && signatureFile != null ->
                    return@Tool pgpFail("signature 与 signature_file 只能传一个")
                signatureRaw != null -> signatureRaw.toByteArray(Charsets.UTF_8)
                signatureFile != null -> {
                    val expandedSig = AgentWorkspace.expand(signatureFile)
                    PathSafetyGuard.check(expandedSig)?.let { v ->
                        return@Tool pgpFail("签名文件路径受限（$signatureFile）: ${v.detail}")
                    }
                    runCatching { java.io.File(expandedSig).readBytes() }
                        .getOrElse { return@Tool pgpFail("读签名文件失败（$signatureFile）: ${it.message}") }
                }
                else -> return@Tool pgpFail("signature 与 signature_file 必须传其一")
            }
        val verifierKey = material.publicKey ?: material.privateKey
            ?: return@Tool pgpFail("key 未解析到可用密钥")
        when (val result = PgpCrypto.verify(verifierKey, payload, signature)) {
            is PgpCrypto.VerifyResult.Valid ->
                listOf(UIMessagePart.Text("✅ 验签通过（签名者指纹 ${result.fingerprint}）"))
            is PgpCrypto.VerifyResult.Invalid ->
                listOf(UIMessagePart.Text("❌ 验签失败：${result.reason}"))
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
        val keyRes = resolvePgpKey(repository, o["key"]?.jsonPrimitive?.contentOrNull, caller = "pgp-encrypt")
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val payload = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "载荷读取失败") }
        val recipientKey = material.publicKey ?: material.privateKey
            ?: return@Tool pgpFail("key 未解析到可用公钥")
        val ciphertext =
            try {
                PgpCrypto.encrypt(recipientKey, payload)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("加密失败: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_encrypt") }
        listOf(UIMessagePart.Text("✅ GPG 加密完成（armored 密文）：\n\n$ciphertext"))
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
        val keyRes = resolvePgpKey(repository, o["key"]?.jsonPrimitive?.contentOrNull, caller = "pgp-decrypt", context = context)
        if (keyRes is PgpKeyResolution.Err) return@Tool pgpFail(keyRes.message)
        val material = (keyRes as PgpKeyResolution.Ok).material
        val ciphertext = readPgpPayload(o).getOrElse { return@Tool pgpFail(it.message ?: "载荷读取失败") }
        val privateKey = material.privateKey ?: return@Tool pgpFail("解密需要私钥（当前 key 是公钥）")
        val passphrase = resolvePgpPassphrase(repository, o, caller = "pgp-decrypt", context = context)
        val plaintext =
            try {
                PgpCrypto.decrypt(privateKey, ciphertext, passphrase)
            } catch (e: PgpCryptoException) {
                return@Tool pgpFail("解密失败: ${e.message}")
            }
        material.entryName?.let { repository.logAccess(it, "ai-tool", "pgp_decrypt") }
        listOf(UIMessagePart.Text("✅ GPG 解密结果：\n${plaintext.toString(Charsets.UTF_8)}"))
    },
)
