package me.rerere.workspace

import java.io.File

/**
 * Install CA certificates into the sandbox rootfs.
 *
 * The Android system CA store (`/system/etc/security/cacerts`, one PEM per file) is invisible
 * inside proot, so the app process reads it and writes a merged bundle where the rootfs
 * toolchains (https apt sources / pip / npm / git) expect it:
 * - `<rootfs>/etc/ssl/certs/ca-certificates.crt` (Debian / Ubuntu / Alpine)
 * - `<rootfs>/etc/ssl/cert.pem` (Alpine also reads this path)
 *
 * Minimal rootfs images may ship without the ca-certificates package, leaving https broken;
 * this is the repair path. Rewriting is idempotent.
 */

/** Merged bundle location relative to the rootfs root (`linuxDir`). */
const val CA_BUNDLE_PATH = "etc/ssl/certs/ca-certificates.crt"
private const val CA_CERT_PEM_PATH = "etc/ssl/cert.pem"

/**
 * Merge PEM files from [systemCaDir] and write them into the rootfs at [linuxDir].
 *
 * @param force when false and the bundle already exists, do nothing (returns 0).
 * @return number of certificates written (0 when skipped).
 */
fun installCaCerts(
    linuxDir: File,
    systemCaDir: File = File("/system/etc/security/cacerts"),
    force: Boolean = true,
): Result<Int> =
    runCatching {
        val bundleFile = File(linuxDir, CA_BUNDLE_PATH)
        if (!force && bundleFile.isFile) return@runCatching 0
        val pems =
            systemCaDir.listFiles { f -> f.isFile }
                ?.sortedBy { it.name }
                ?.mapNotNull { f ->
                    runCatching { f.readText() }.getOrNull()?.takeIf { it.contains("BEGIN CERTIFICATE") }
                }
                .orEmpty()
        require(pems.isNotEmpty()) { "no CA certificates found in ${systemCaDir.path}" }
        val bundle = pems.joinToString("\n") { it.trim() } + "\n"
        bundleFile.parentFile?.mkdirs()
        bundleFile.writeText(bundle)
        // Alpine resolves CAs via /etc/ssl/cert.pem as well
        if (readRootfsDistro(linuxDir)?.id == "alpine") {
            val pem = File(linuxDir, CA_CERT_PEM_PATH)
            pem.parentFile?.mkdirs()
            pem.writeText(bundle)
        }
        pems.size
    }
