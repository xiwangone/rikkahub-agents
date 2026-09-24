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
    val apt: String = "",
    val pip: String = "",
    val npm: String = "",
) {
    val isEmpty: Boolean get() = apk.isBlank() && apt.isBlank() && pip.isBlank() && npm.isBlank()
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

    /**
     * Debian 系（Ubuntu / Debian）的 apt 源。
     *
     * ⚠ 与其他三个不同，这里的 [WorkspaceMirrorPreset.url] 是 **base**（不含路径与套件）——
     * 实际源地址取决于 rootfs 的发行版与架构（`/ubuntu/`、`/ubuntu-ports/`、`/debian/`），
     * 由 [applyWorkspaceMirrors] 依据 rootfs 的 `/etc/os-release` 拼装。空 base 表示「官方源」。
     */
    val APT =
        listOf(
            WorkspaceMirrorPreset("official", "Official", "", "Global"),
            WorkspaceMirrorPreset("tuna", "Tsinghua TUNA", "https://mirrors.tuna.tsinghua.edu.cn", "China"),
            WorkspaceMirrorPreset("aliyun", "Alibaba", "http://mirrors.aliyun.com", "China"),
            WorkspaceMirrorPreset("ustc", "USTC", "https://mirrors.ustc.edu.cn", "China"),
            WorkspaceMirrorPreset("huawei", "Huawei", "https://repo.huaweicloud.com", "China"),
            WorkspaceMirrorPreset("tencent", "Tencent", "https://mirrors.cloud.tencent.com", "China"),
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
private const val OS_RELEASE_PATH = "etc/os-release"
private const val UBUNTU_SOURCES_PATH = "etc/apt/sources.list.d/ubuntu.sources"
private const val DEBIAN_SOURCES_PATH = "etc/apt/sources.list.d/debian.sources"
private const val MIRROR_MARKER = "# managed by workspace mirror"

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

        // apt（Ubuntu / Debian）：源地址随发行版与架构变化，按 rootfs 的 /etc/os-release 生成 deb822
        val aptBase = mirrors.apt.trimEnd('/')
        if (aptBase.isBlank()) {
            // 留空 = 回到镜像默认源：只删除本功能写过的文件（带标记），不碰系统自带的源
            listOf(UBUNTU_SOURCES_PATH, DEBIAN_SOURCES_PATH).forEach { rel ->
                val file = File(linuxDir, rel)
                val managed =
                    file.isFile && runCatching { file.readText().contains(MIRROR_MARKER) }.getOrDefault(false)
                if (managed) file.delete()
            }
        } else {
            val distro = readRootfsDistro(linuxDir)
            if (distro != null) {
                when (distro.id) {
                    "ubuntu" -> writeOrDelete(File(linuxDir, UBUNTU_SOURCES_PATH), ubuntuSources(aptBase, distro.codename))
                    "debian" -> writeOrDelete(File(linuxDir, DEBIAN_SOURCES_PATH), debianSources(aptBase, distro.codename))
                    // 其他发行版（或非 Debian 系）：不动，保持镜像自带源
                    else -> Unit
                }
            }
        }
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

/** rootfs 的发行版标识（取自 `/etc/os-release`）。 */
private data class RootfsDistro(val id: String, val codename: String)

/** 读取 rootfs 的 `/etc/os-release`；缺文件或缺关键字段时返回 null（此时不改动 apt 源）。 */
private fun readRootfsDistro(linuxDir: File): RootfsDistro? {
    val file = File(linuxDir, OS_RELEASE_PATH)
    if (!file.isFile) return null
    val text = runCatching { file.readText() }.getOrNull() ?: return null
    fun field(key: String): String? =
        text.lineSequence()
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    val id = field("ID") ?: return null
    val codename = field("VERSION_CODENAME") ?: field("VERSION_CODENAME_LIKE") ?: return null
    return RootfsDistro(id, codename)
}

/**
 * 运行设备是否 x86 —— apt 源路径 `/ubuntu/` 与 `/ubuntu-ports/` 的分界。
 * rootfs 必须匹配设备 ABI，故直接取进程架构即可。
 */
private fun isX86Host(): Boolean =
    System.getProperty("os.arch").orEmpty().lowercase().let { it == "x86_64" || it == "amd64" }

/** Ubuntu 的 deb822 源内容（主源含 updates / security / backports）。 */
private fun ubuntuSources(base: String, codename: String): String {
    val path = if (isX86Host()) "/ubuntu/" else "/ubuntu-ports/"
    return buildString {
        appendLine(MIRROR_MARKER)
        appendLine("Types: deb")
        appendLine("URIs: $base$path")
        appendLine("Suites: $codename $codename-updates $codename-security $codename-backports")
        appendLine("Components: main universe restricted multiverse")
        appendLine("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg")
    }
}

/** Debian 的 deb822 源内容（主源 + 独立 debian-security 段）。 */
private fun debianSources(base: String, codename: String): String =
    buildString {
        appendLine(MIRROR_MARKER)
        appendLine("Types: deb")
        appendLine("URIs: $base/debian/")
        appendLine("Suites: $codename $codename-updates")
        appendLine("Components: main contrib non-free non-free-firmware")
        appendLine("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg")
        appendLine()
        appendLine("Types: deb")
        appendLine("URIs: $base/debian-security/")
        appendLine("Suites: $codename-security")
        appendLine("Components: main contrib non-free non-free-firmware")
        appendLine("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg")
    }

private fun writeOrDelete(file: File, content: String?) {
    if (content == null) {
        if (file.exists()) file.delete()
        return
    }
    file.parentFile?.mkdirs()
    file.writeText(content)
}
