package me.rerere.rikkahub.data.vault

import android.icu.text.Transliterator
import java.text.Normalizer

/**
 * 凭证名的规范化（纯函数，与仓库解耦，便于单测）。
 *
 * 背景：旧的 [CredentialVaultRepository.normalizeName] 用 `[^A-Z0-9_]+` 一刀切，
 * 中文名整段被换成下划线、最后只剩 `CRED` —— 所有纯中文名导入都撞到同一个名字，
 * 后一个还会覆盖前一个的值。这里先把中文转拼音再折叠（七牛云 → QINIUYUN），
 * 只有实在拼不出来的字符才按旧规则丢弃。
 */
internal object CredentialNameNormalizer {

    /** 拼音转换覆盖的汉字区段：扩展 A / 统一表意 / 兼容表意。 */
    private fun Char.isCjk(): Boolean =
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

    /** Han-Latin 音译器（惰性创建；平台不可用时为 null，调用方回退旧行为，不抛异常）。 */
    private val hanLatinTransliterator: Transliterator? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { Transliterator.getInstance("Han-Latin") }.getOrNull()
    }

    /**
     * 把中文转成不带声调的拼音（其余字符原样保留）。
     *
     * - 无汉字时直接返回原串（不碰音译器：纯 JVM 单测与纯英文名都不受影响）；
     * - 音译器不可用（纯 JVM 环境）或转换失败时返回原串，由后续正则按旧规则处理；
     * - [transliterate] 仅供单测注入假音译；生产路径用默认实现。
     */
    fun transliterate(raw: String, transliterator: (String) -> String = ::defaultTransliterate): String =
        transliterator(raw)

    private fun defaultTransliterate(raw: String): String {
        if (raw.none { it.isCjk() }) return raw
        val transliterator = hanLatinTransliterator ?: return raw
        val latin = runCatching { synchronized(transliterator) { transliterator.transliterate(raw) } }.getOrNull()
            ?: return raw
        // 去声调：NFD 分解后删掉组合标记（ā → a + ̄ → a；lü → lu）。
        return Normalizer.normalize(latin, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    }

    /**
     * 折叠成大写蛇形片段（不含前缀与空值回退，供编辑器输入时逐字调用）。
     *
     * 与旧行为一致：非法字符折成下划线；编辑器里空格同样折成下划线（边输边规范化，
     * 保存时不再二次变形）。
     */
    fun foldNameChars(raw: String, transliterate: (String) -> String = ::defaultTransliterate): String =
        transliterate(raw).uppercase().replace(Regex("[^A-Z0-9_ ]"), "_").replace(" ", "_")

    /**
     * 把任意输入规范化成合规凭证名（`^[A-Z][A-Z0-9_]*$`，最长 64 字符）。
     *
     * 用于"粘贴即入库"：用户往往直接粘一段描述或小写名，这里统一转拼音 + 大写蛇形，
     * 非法字符折成下划线；首字符不是字母时补前缀，保证结果始终合法。
     */
    fun normalizeName(raw: String, transliterate: (String) -> String = ::defaultTransliterate): String {
        val folded = foldNameChars(raw, transliterate).replace(Regex("[^A-Z0-9_]+"), "_").trim('_')
        // 没有任何可用字符（空串 / 纯符号 / 纯中文但音译不可用）→ 明确标"未命名"，
        // 而不是落到裸 CRED（旧代码的 `CRED_$folded` 再 trim 会把 `CRED_` 削成 `CRED`，
        // 于是 `CRED_UNNAMED` 这个兜底永远走不到）。
        if (folded.isEmpty()) return "CRED_UNNAMED"
        val prefixed = if (!folded[0].isLetter()) "CRED_$folded" else folded
        return prefixed.take(64).trim('_').ifEmpty { "CRED_UNNAMED" }
    }

    /**
     * 是否退化名：输入里没有任何可用字符时 `normalizeName` 的兜底产物。
     *
     * `CRED`（空/纯中文/纯符号输入）、`CRED_UNNAMED`，以及同名碰撞后加的数字后缀
     * （`CRED_2`……）——这类名字不携带任何凭证信息，重导带新名时应被改名而不是复用。
     */
    fun isDegenerateName(name: String): Boolean =
        name == "CRED" || name == "CRED_UNNAMED" || name.matches(Regex("^CRED_[0-9]+$"))

    /**
     * 在已占用的名字里找一个未被占用的变体（`BASE`、`BASE_2`、`BASE_3`……）。
     *
     * 后缀计入 64 字符上限（超长时截断本体再拼后缀，不会超长）。
     */
    suspend fun uniqueName(base: String, isTaken: suspend (String) -> Boolean): String {
        if (!isTaken(base)) return base
        var n = 2
        while (true) {
            val suffix = "_$n"
            val candidate = base.take((64 - suffix.length).coerceAtLeast(0)).trimEnd('_') + suffix
            if (!isTaken(candidate)) return candidate
            n++
        }
    }
}
