package me.rerere.workspace

import java.io.File

/**
 * Package-manager mirrors for the sandbox rootfs.
 *
 * Empty values mean "official source" — the corresponding config file is then removed so the
 * rootfs falls back to its default. Values are base URLs of the mirror.
 */
data class WorkspaceMirrors(
    val apk: String = "",
    val pip: String = "",
    val npm: String = "",
) {
    val isEmpty: Boolean get() = apk.isBlank() && pip.isBlank() && npm.isBlank()
}

/** One selectable mirror: [label] is shown in the picker, URLs are the base addresses. */
data class WorkspaceMirrorPreset(
    val id: String,
    val label: String,
    val url: String,
    val region: String,
)

/** Preset mirrors for the three package managers used inside the sandbox. */
object WorkspaceMirrorPresets {

    val APK =
        listOf(
            WorkspaceMirrorPreset("official", "Official CDN", "https://dl-cdn.alpinelinux.org/alpine/", "Global"),
            WorkspaceMirrorPreset("tuna", "Tsinghua TUNA", "https://mirrors.tuna.tsinghua.edu.cn/alpine/", "China"),
            WorkspaceMirrorPreset("aliyun", "Alibaba", "https://mirrors.aliyun.com/alpine/", "China"),
            WorkspaceMirrorPreset("ustc", "USTC", "https://mirrors.ustc.edu.cn/alpine/", "China"),
            WorkspaceMirrorPreset("huawei", "Huawei", "https://repo.huaweicloud.com/alpine/", "China"),
            WorkspaceMirrorPreset("tencent", "Tencent", "https://mirrors.cloud.tencent.com/alpine/", "China"),
        )

    val PIP =
        listOf(
            WorkspaceMirrorPreset("official", "Official PyPI", "https://pypi.org/simple/", "Global"),
            WorkspaceMirrorPreset("tuna", "Tsinghua TUNA", "https://pypi.tuna.tsinghua.edu.cn/simple/", "China"),
            WorkspaceMirrorPreset("aliyun", "Alibaba", "https://mirrors.aliyun.com/pypi/simple/", "China"),
            WorkspaceMirrorPreset("ustc", "USTC", "https://mirrors.ustc.edu.cn/pypi/web/simple/", "China"),
            WorkspaceMirrorPreset("huawei", "Huawei", "https://repo.huaweicloud.com/repository/pypi/simple/", "China"),
            WorkspaceMirrorPreset("tencent", "Tencent", "https://mirrors.cloud.tencent.com/pypi/simple/", "China"),
        )

    val NPM =
        listOf(
            WorkspaceMirrorPreset("official", "Official npm", "https://registry.npmjs.org/", "Global"),
            WorkspaceMirrorPreset("npmmirror", "npmmirror", "https://registry.npmmirror.com/", "China"),
            WorkspaceMirrorPreset("huawei", "Huawei", "https://repo.huaweicloud.com/repository/npm/", "China"),
            WorkspaceMirrorPreset("tencent", "Tencent", "https://mirrors.cloud.tencent.com/npm/", "China"),
        )
}

private const val APK_REPOS_PATH = "etc/apk/repositories"
private const val PIP_CONF_PATH = "etc/pip.conf"
private const val NPMRC_PATH = "etc/npmrc"
private const val ALPINE_RELEASE_PATH = "etc/alpine-release"

/**
 * Write mirror configuration into the sandbox rootfs.
 *
 * A blank value removes the file (back to the rootfs default). `apk` uses the running Alpine
 * release branch (`vX.Y`) so repositories keep matching the installed system; if the release
 * file is missing the branch is omitted and only the mirror base is written.
 */
fun applyWorkspaceMirrors(
    linuxDir: File,
    mirrors: WorkspaceMirrors,
): Result<Unit> =
    runCatching {
        val version = readAlpineBranch(linuxDir)

        writeOrDelete(
            File(linuxDir, APK_REPOS_PATH),
            mirrors.apk.takeIf { it.isNotBlank() }?.let { base ->
                val root = base.trimEnd('/')
                val branch = version?.let { "/$it" }.orEmpty()
                "$root$branch/main\n$root$branch/community\n"
            },
        )

        writeOrDelete(
            File(linuxDir, PIP_CONF_PATH),
            mirrors.pip.takeIf { it.isNotBlank() }?.let { url ->
                "[global]\nindex-url = ${url.trimEnd('/')}/\ntrusted-host = ${url.substringAfter("://").substringBefore('/')}\n"
            },
        )

        writeOrDelete(
            File(linuxDir, NPMRC_PATH),
            mirrors.npm.takeIf { it.isNotBlank() }?.let { url -> "registry=${url.trimEnd('/')}/\n" },
        )
    }

/** `3.21.2` → `v3.21`; null when the release file is absent or unparsable. */
private fun readAlpineBranch(linuxDir: File): String? {
    val release = File(linuxDir, ALPINE_RELEASE_PATH)
    if (!release.isFile) return null
    val raw = runCatching { release.readText().trim() }.getOrNull() ?: return null
    val parts = raw.split('.')
    if (parts.size < 2) return null
    return "v${parts[0]}.${parts[1]}"
}

private fun writeOrDelete(file: File, content: String?) {
    if (content == null) {
        if (file.exists()) file.delete()
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(content)
}
