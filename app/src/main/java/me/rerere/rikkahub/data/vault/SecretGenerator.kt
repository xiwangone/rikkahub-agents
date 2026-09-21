package me.rerere.rikkahub.data.vault

import java.security.SecureRandom
import java.util.Base64

/**
 * 随机秘密值生成器（凭证库「生成随机值」用）。
 *
 * 用途：给 API key / 访问令牌 / 口令类凭证生成高熵初值，用户在 App 内当场复制，
 * 只以密文入库。
 *
 * ⚠ 刻意**不接入 AI 工具**（`vault_gen_key` 是模型可调用的）：由模型生成并把明文回传，
 * 等于把新密钥写进对话上下文，与「AI 不见明文」的纪律冲突 —— 这类值走 UI 才是正确路径。
 */
object SecretGenerator {

    private val secureRandom = SecureRandom()

    // 各字符集均去掉易混字符（0/O/o、1/l/I），避免用户手抄出错
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#\$%^&*-_=+?"

    /** URL 安全的高熵 token（默认 32 字节 → 43 个字符、无填充）；适合 API key / 访问令牌。 */
    fun token(bytes: Int = 32): String {
        require(bytes >= 16) { "token needs at least 16 bytes" }
        val buffer = ByteArray(bytes)
        secureRandom.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    /**
     * 高熵可读密码（默认 20 位）。
     * 保证大写、小写、数字、符号各至少一位（便于通过常见的强度校验），
     * 并通过洗牌避免「类别固定落在前几位」。
     */
    fun password(length: Int = 20): String {
        require(length >= 8) { "password needs at least 8 characters" }
        val all = UPPER + LOWER + DIGITS + SYMBOLS
        val chars = MutableList(length) { all[secureRandom.nextInt(all.length)] }
        listOf(UPPER, LOWER, DIGITS, SYMBOLS).forEachIndexed { index, pool ->
            chars[index] = pool[secureRandom.nextInt(pool.length)]
        }
        for (i in chars.indices.reversed()) {
            val j = secureRandom.nextInt(i + 1)
            val swap = chars[i]
            chars[i] = chars[j]
            chars[j] = swap
        }
        return chars.joinToString("")
    }
}
