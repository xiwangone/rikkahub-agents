package me.rerere.rikkahub.data.ai.tools.local

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64

/**
 * Auth bundle. Any combination is attemptable:
 * - [password] and [privateKey] both blank (and no [certificate]) means none
 *   authentication: the server must accept it (token-username relays).
 * - [certificate] is an OpenSSH user certificate; it requires its matching
 *   [privateKey] and is validated before connect, never silently falling back.
 * - [proxyCommand] runs a local command as the transport (%h/%p expand to
 *   the target host/port); it owns routing, so no tunnel or local resolution
 *   runs when it is set.
 */
internal data class SshAuth(
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
    val certificate: String? = null,
    val proxyCommand: String? = null,
)

/** Every auth bundle is attemptable: empty credentials mean none authentication. */
internal fun SshAuth.isUsable() = true

/**
 * 凭证材料的缓存指纹：私钥/密码/证书/代理命令分别加 `key:`/`pw:`/`cert:`/`proxy:`
 * 前缀再取 SHA-256 hex，含 passphrase。只存摘要不存明文；同一份凭证每次算出的
 * 指纹相同，不同凭证必然不同键。旧字段都空时材料仍为 "none:"，与改动前一致，
 * 新字段只在非空时追加，不会让旧主机复用不同认证的会话。
 */
internal fun SshAuth.cacheFingerprint(): String {
    val parts = listOfNotNull(
        privateKey?.takeIf { it.isNotBlank() }?.let { "key:$it|pass:${passphrase.orEmpty()}" },
        password?.takeIf { it.isNotBlank() }?.let { "pw:$it" },
        certificate?.takeIf { it.isNotBlank() }?.let { "cert:$it" },
        proxyCommand?.takeIf { it.isNotBlank() }?.let { "proxy:$it" },
    )
    val material = parts.joinToString("|").ifEmpty { "none:" }
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * PreferredAuthentications for [auth]: a bundle with no credential material
 * (password, private key and certificate all blank) means none authentication,
 * so "none" goes first; a bundle with any credential keeps the previous order
 * and never offers none.
 */
internal fun preferredAuthentications(auth: SshAuth): String =
    if (auth.password.isNullOrBlank() && auth.privateKey.isNullOrBlank() && auth.certificate.isNullOrBlank()) {
        "none,publickey,keyboard-interactive,password"
    } else {
        "publickey,keyboard-interactive,password"
    }

/** First non-blank line of [text], trimmed; null when [text] has none. */
internal fun firstNonBlankLine(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

/** Parsed OpenSSH certificate: identity fields only, the signature is not verified here. */
internal data class SshCertificateInfo(
    val keyType: String,
    val isUserCertificate: Boolean,
    val validAfterEpochSeconds: Long,
    val validBeforeEpochSeconds: Long,
)

/**
 * Canonical base64: basic alphabet with correct padding. `Regex.matches`
 * requires a full match, so this accepts exactly what `Base64.getDecoder`
 * accepts; decoding is then total and needs no try-catch.
 */
private val BASE64_PATTERN =
    Regex("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?")

private fun isBase64(text: String): Boolean = BASE64_PATTERN.matches(text)

/**
 * Parse an OpenSSH certificate line (the contents of a `-cert.pub` file).
 * Throws [IllegalArgumentException] with a plain reason when the text is not
 * a well-formed certificate. `validBeforeEpochSeconds == -1L` means the
 * certificate never expires (OpenSSH "forever" marker).
 */
internal fun parseSshCertificate(certificateText: String): SshCertificateInfo {
    val line = firstNonBlankLine(certificateText)
        ?: throw IllegalArgumentException("ssh certificate is empty")
    val fields = line.split(Regex("\\s+"))
    if (fields.size < 2 || !fields[0].endsWith("-cert-v01@openssh.com")) {
        throw IllegalArgumentException("ssh certificate is not an OpenSSH certificate line")
    }
    if (!isBase64(fields[1])) {
        throw IllegalArgumentException("ssh certificate is not valid base64")
    }
    val blob = Base64.getDecoder().decode(fields[1])
    val reader = SshWireReader(blob)
    val keyType = reader.readString()
    reader.readString() // nonce
    reader.readString() // embedded public key
    reader.readLong() // serial
    val type = reader.readInt()
    reader.readString() // key id
    reader.readString() // valid principals
    val validAfter = reader.readLong()
    val validBefore = reader.readLong()
    return SshCertificateInfo(
        keyType = keyType,
        isUserCertificate = type == 1,
        validAfterEpochSeconds = validAfter,
        validBeforeEpochSeconds = validBefore,
    )
}

/**
 * Parse [certificateText] and check it is a user certificate valid at
 * [nowEpochSeconds]. Throws [JSchException] with a plain reason when the text
 * is malformed, is a host certificate, is not yet valid, or is expired.
 * Expired and not-yet-valid certificates are rejected here, before any
 * connection attempt, so the failure is reported instead of silently falling
 * back to another authentication method.
 */
internal fun validateSshCertificate(
    certificateText: String,
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
): SshCertificateInfo {
    val info = try {
        parseSshCertificate(certificateText)
    } catch (e: IllegalArgumentException) {
        throw JSchException("ssh certificate could not be parsed: ${e.message}", e)
    }
    if (!info.isUserCertificate) {
        throw JSchException("ssh certificate is a host certificate, a user certificate is required")
    }
    if (nowEpochSeconds < info.validAfterEpochSeconds) {
        throw JSchException(
            "ssh certificate is not yet valid (valid from ${Instant.ofEpochSecond(info.validAfterEpochSeconds)})"
        )
    }
    if (info.validBeforeEpochSeconds != -1L && nowEpochSeconds >= info.validBeforeEpochSeconds) {
        throw JSchException(
            "ssh certificate expired (valid until ${Instant.ofEpochSecond(info.validBeforeEpochSeconds)})"
        )
    }
    return info
}

/**
 * Load the identity of [auth] into [jsch], the way a plain private key was
 * loaded before. A certificate is validated first (malformed, host, not yet
 * valid, expired all fail here), requires its matching private key, and never
 * falls back to another method; without a certificate the plain key path is
 * unchanged, and with neither there is nothing to load (none authentication).
 */
internal fun installSshIdentity(jsch: JSch, auth: SshAuth) {
    val certificate = auth.certificate?.takeIf { it.isNotBlank() }
    if (certificate != null) {
        validateSshCertificate(certificate)
        val key = auth.privateKey?.takeIf { it.isNotBlank() }
            ?: throw JSchException("ssh certificate requires a private key")
        val certificateLine = firstNonBlankLine(certificate)
            ?: throw JSchException("ssh certificate is empty")
        try {
            jsch.addIdentity(
                "rikkahub-ssh-cert-${System.nanoTime()}",
                key.withTrailingNewline().toByteArray(Charsets.UTF_8),
                certificateLine.toByteArray(Charsets.UTF_8),
                auth.passphrase?.toByteArray(Charsets.UTF_8),
            )
        } catch (e: JSchException) {
            throw JSchException("ssh certificate could not be loaded: ${e.message}", e)
        }
        return
    }
    if (!auth.privateKey.isNullOrBlank()) {
        jsch.addIdentity(
            "rikkahub-ssh-key-${System.nanoTime()}",
            auth.privateKey.withTrailingNewline().toByteArray(Charsets.UTF_8),
            null,
            auth.passphrase?.toByteArray(Charsets.UTF_8),
        )
    }
}

/** PEM text needs a trailing newline or JSch rejects it at load time. */
private fun String.withTrailingNewline(): String = if (endsWith("\n")) this else "$this\n"

/** Minimal reader for SSH wire format: length-prefixed strings, uint32, uint64.
 * Overrunning the buffer means the certificate is truncated, so the reader
 * throws [IllegalArgumentException] directly and callers need no catch. */
private class SshWireReader(private val bytes: ByteArray) {
    private var offset = 0

    fun readString(): String {
        val length = readInt()
        if (length < 0 || offset + length > bytes.size) {
            throw IllegalArgumentException("ssh certificate is truncated")
        }
        val value = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8)
        offset += length
        return value
    }

    fun readInt(): Int {
        if (offset + 4 > bytes.size) {
            throw IllegalArgumentException("ssh certificate is truncated")
        }
        return ByteBuffer.wrap(bytes, offset, 4).int.also { offset += 4 }
    }

    fun readLong(): Long {
        if (offset + 8 > bytes.size) {
            throw IllegalArgumentException("ssh certificate is truncated")
        }
        return ByteBuffer.wrap(bytes, offset, 8).long.also { offset += 8 }
    }
}
