package me.rerere.rikkahub.data.ai.tools

/**
 * 校验八进制文件权限（如 `755` / `0644`）。
 *
 * 返回 null 表示「不设置权限」（保持默认，即不可执行）。非法输入直接抛错 —— 该值会拼进
 * rootfs 内的 shell 命令，必须先限定为 3-4 位八进制，避免命令注入。
 */
internal fun sanitizeFileMode(mode: String?): String? {
    val raw = mode?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (!raw.matches(Regex("^[0-7]{3,4}$"))) {
        error("mode must be 3-4 octal digits (e.g. 755 or 0644), got: $mode")
    }
    return raw
}
