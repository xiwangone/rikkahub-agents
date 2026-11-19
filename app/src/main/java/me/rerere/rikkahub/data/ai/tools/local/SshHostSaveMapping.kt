package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.common.http.optInt
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.datastore.SshPlatform
import me.rerere.rikkahub.data.db.entity.SshHostEntity

/**
 * Build a [SshHostEntity] from save_ssh_host parameters. Extracted from the
 * tool body so the mapping is unit-testable without a repository.
 *
 * Security boundary: `proxy_command` is deliberately NOT read here — it is not
 * in the tool's parameter schema, so a model-supplied value is ignored even if
 * present in the input. The proxy command is a local command (it runs on the
 * device); only the user may set it, from the settings UI.
 */
internal fun sshHostEntityFromSaveInput(p: JsonObject, nowMs: Long): SshHostEntity {
    val name = p.optString("name") ?: error("name is required")
    val host = p.optString("host") ?: error("host is required")
    val user = p.optString("user") ?: error("user is required")
    val port = p.optInt("port") ?: 22
    val password = p.optString("password")
    val privateKey = p.optString("private_key")
    val passphrase = p.optString("passphrase")
    val vaultCredential = p.optString("vault_credential")
    val certificate = p.optString("certificate")
    val vaultCertificate = p.optString("vault_certificate")
    val fallbackHosts = p["fallback_hosts"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val jumpHost = p.optString("jump_host")
    val platformParam = p.optString("platform")
    val sshOptions = p.optString("ssh_options")
    return SshHostEntity(
        name = name, host = host, port = port, user = user,
        password = if (vaultCredential.isNullOrBlank()) password else null,
        privateKey = if (vaultCredential.isNullOrBlank()) privateKey else null,
        passphrase = passphrase,
        vaultCredentialRef = vaultCredential,
        certificate = if (vaultCertificate.isNullOrBlank()) certificate else null,
        vaultCertificateRef = vaultCertificate,
        fallbackHostsJson = fallbackHosts?.takeIf { it.isNotEmpty() }
            ?.let { buildJsonArray { it.forEach { f -> add(f) } }.toString() },
        jumpHost = jumpHost,
        platform = platformParam?.let { SshPlatform.fromPreference(it).name } ?: "AUTO",
        sshOptions = sshOptions,
        createdAtMs = nowMs,
    )
}
