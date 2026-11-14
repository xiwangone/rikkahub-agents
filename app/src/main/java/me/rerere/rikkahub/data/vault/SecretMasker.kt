package me.rerere.rikkahub.data.vault

import java.util.Base64
import me.rerere.rikkahub.utils.KnownSecretPrefixes

/**
 * 凭证输出掩码器（P0）。
 *
 * 目标：AI 工具输出中即使意外出现凭证明文，也不让其进入 LLM 上下文。
 * 策略（既定方案）：
 * - **只对密钥库（Vault）内容掩码**——掩码字典 = 密钥库全部凭证的明文值（按规范名称索引）
 * - **随加随掩**——refresh() 时按凭证表版本比对，新增/修改凭证自动纳入，无需改代码
 * - 缓存化——凭证未变更时复用内存字典，避免每次全量解密
 * - 不做格式正则猜测（避免误伤、避免掩码库外内容）
 *
 * 安全：掩码发生在 App 进程内、内存态，解密值不落盘、不进 AI 上下文。
 *
 * 派生形态（P2-9）：精确替换之外，同一秘密的以下形态同样掩码——base64（标准/URL 安全、
 * 带/不带填充）、倒序、逐字符分隔（字符间插空白/`-`/`_`等分隔符）。只对长度 ≥
 * [DERIVED_MIN_LEN] 的秘密做派生（短值的派生形态误掩面太大，如倒序 "ba" 会误伤正常文本）。
 * 残余缺口：秘密被拆分到多次工具调用、每次只出一部分时，单次掩码看不到完整形态，掩不住；
 * 整个 PEM 私钥块被整体 base64 后也不在覆盖内（结构正则认不出块边界）。
 * 与统一 JSON 脱敏器（`ai.util.Json.redactSecrets`，SSE 与日志共用、按键名脱敏）分层互补：
 * 那套管结构化 JSON，本文件管自由文本里的值形态，不另起一套打架。
 */
/** 一条凭证对应的掩码规则。exact=true 按完整值精确替换；私钥类(exact=false)靠 PEM 结构正则兜底。 */
data class SecretRule(
    val value: String,
    val exact: Boolean,
)

object SecretMasker {
    private const val MASK = "***"

    // 非敏感短值下限：名称不含敏感词且短于此长度不掩（chat_id/alias 等，防包名式误伤）
    private const val MIN_LEN = 9

    /** 派生形态（base64/倒序/逐字符分隔）只对不短于此长度的秘密做，避免短值误掩。 */
    private const val DERIVED_MIN_LEN = 8

    /** 逐字符分隔正则只对不长于此长度的秘密做，避免超长正则拖慢每次掩码。 */
    private const val SEPARATOR_TOLERANT_MAX_LEN = 64

    // 敏感名称词：命中即掩。但"无条件"只适用于高熵秘密（长随机值）；alias/短标识
    // （如签名 alias = "rikkahub" 品牌名）全文替换会误伤文档/包名里的同串。
    // 故：名称命中敏感词 + 值 ≥ MIN_LEN → 掩；值短于 MIN_LEN → 仍需掩（短密码 6 位），
    // 但仅当值像秘密（非纯品牌词：含大小写混合/数字/符号，或不在常见词表）。
    private val SENSITIVE_WORDS = listOf(
        "PASSWORD", "PWD", "PASS", "TOKEN", "SECRET", "APIKEY", "API_KEY",
        "AUTH", "PRIVATE", "KEY",
    )
    // 品牌/项目名等低熵词：出现在 alias/路径/注释里，全文替换会误伤文档与代码包名。
    // 命中这些词的值若短且无高熵特征，不收为精确替换规则（结构层 PEM 正则仍兜私钥）。
    private val BRAND_WORDS = listOf("rikkahub", "reasonix")

    // PEM 私钥结构：BEGIN..END PRIVATE KEY 块（DOTALL 容忍 base64 折行，兜住字典精确匹配漏网）
    private val PEM_PRIVATE_KEY = Regex(
        "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // 通用密钥形态：不依赖凭证表即可命中（未登记凭证 / 临时 token 的兜底）。
    // 口径见 KnownSecretPrefixes：与日志脱敏共用同一张前缀表，阈值取更敏感的一档。
    private val GENERIC_SECRET_PATTERNS = KnownSecretPrefixes.patternRegexes()

    // 公钥值形态：以这些开头的是公钥（可公开，不掩——authorized_keys 本就公开贴）
    private val PUBLIC_KEY_PREFIX = listOf(
        "ssh-rsa ", "ssh-ed25519 ", "ecdsa-sha2-", "ssh-dss ",
        "sk-ssh-ed25519 ", "sk-ssh-ecdsa-",
    )

    // 逐字符分隔形态允许的分隔符：空白与常见连接符（base64/倒序绕过常配这些分隔）。
    private val SEPARATOR_CLASS = """[\s\-_.,:;]*"""

    // 缓存：凭证表版本（max updatedAt）→ 掩码规则
    @Volatile
    private var cachedVersion: Long = -1

    @Volatile
    internal var cachedRules: List<SecretRule> = emptyList()

    /** 刷新掩码规则（suspend）。凭证未变更时走缓存。 */
    suspend fun refresh(repository: CredentialVaultRepository) {
        val entries = repository.getAll()
        val version = entries.maxOfOrNull { it.updatedAt } ?: 0L
        if (version == cachedVersion) return
        // 先算规则、最后一起写版本：并发场景下先写版本会让其他线程在规则就绪前
        // 命中缓存直接返回（首次冷启动拿到空规则 = 全部凭证不掩）；解密中途抛异常
        // 也只让本次刷新无效，下次仍会重算，不会永久卡在旧规则上。
        val newRules = entries.mapNotNull { e ->
            val v = repository.decryptValue(e)?.trim() ?: return@mapNotNull null
            classify(e.name, v)
        }
        cachedRules = newRules
        cachedVersion = version
    }

    /**
     * 分类（元数据 + 结构 + 长度三层）：
     * - 公钥值（ssh-rsa/ed25519/ecdsa 开头单行）→ null（不掩，非机密）
     * - 私钥（值含 PRIVATE KEY 头尾）→ exact=false（靠结构正则，容忍折行）
     * - 名称命中敏感词（PWD/TOKEN/KEY/...）→ exact=true，无条件掩（无视长度）
     * - 其余：长度 ≥ MIN_LEN 才掩（保底，防包名式误伤 chat_id/alias）
     */
    internal fun classify(name: String, value: String): SecretRule? {
        val n = name.uppercase()
        if (PUBLIC_KEY_PREFIX.any { value.startsWith(it) }) return null
        if (value.contains("PRIVATE KEY-----")) {
            return SecretRule(value, exact = false)
        }
        val looksSensitiveName = SENSITIVE_WORDS.any { it in n }
        if (looksSensitiveName) {
            // 名称像敏感（KEY/PASS/TOKEN...）但值是低熵品牌词/短标识时，全文精确替换会
            // 误伤文档与包名（如 alias=rikkahub → 把 rikkahub-agents/package 里的 rikkahub
            // 全掩成 ***）。判定：值含品牌词且整体低熵（无长数字/符号混合）→ 不收精确规则；
            // 高熵短值（如 6 位随机密码）→ 仍收。
            val isBrandish = BRAND_WORDS.any { value.lowercase().contains(it) } &&
                !hasHighEntropy(value)
            if (isBrandish) return null
            return SecretRule(value, exact = true)
        }
        return if (value.length >= MIN_LEN) SecretRule(value, exact = true) else null
    }

    /** 高熵粗判：含 ≥2 组数字/符号或长度 ≥ MIN_LEN 且非纯字母词。低熵品牌词（纯字母/短）返回 false。 */
    private fun hasHighEntropy(v: String): Boolean {
        if (v.length >= MIN_LEN) {
            // 长值默认视为高熵（真秘密）；纯小写单词/常见品牌名除外（仍低熵）
            val alphaOnly = v.all { it.isLetter() || it == '-' || it == '_' || it == '.' }
            return !alphaOnly || v.any { it.isUpperCase() }
        }
        // 短值（< MIN_LEN）：含数字或符号才算高熵（密码常有），纯字母短词视为低熵
        return v.any { it.isDigit() } || v.any { !it.isLetterOrDigit() }
    }

    /** 掩码文本（同步；调用方需先 refresh 保证规则最新）。 */
    fun mask(text: String): String = mask(text, cachedRules)

    /**
     * 掩码文本：通用形态 + PEM 私钥结构正则 + 精确值替换 + 派生形态。
     * 公钥/非敏感短值不受影响。
     */
    fun mask(text: String, rules: Collection<SecretRule>): String {
        var out = text
        // 0) 通用形态层：常见密钥格式（即使未登记在凭证库也能掩）
        GENERIC_SECRET_PATTERNS.forEach { out = it.replace(out, MASK) }
        // 1) 结构层：私钥块全掩（容忍折行/多行，兜住精确匹配漏网）
        out = PEM_PRIVATE_KEY.replace(out, MASK)
        // 2) 精确层：逐条 replace（classify 只收确认为机密的条目——公钥/非敏感短值已排除，
        //    字典量≈机密数；长值优先防短值截断长值错掩）。当前 58 条量级 replace 开销可忽略。
        val exactValues = rules.asSequence()
            .filter { it.exact }
            .map { it.value }
            // 空值/极短值必须排除：空串会让 replace("", MASK) 变成「逐字符插入掩码」，
            // 把整段输出污染成 ***x***x***（空占位凭证即触发此问题）。
            .filter { it.isNotBlank() && it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }
            .toList()
        // 2b) 派生层：同一秘密的 base64 / 倒序形态（P2-9：只做精确匹配会被编码绕过）。
        //    只对 ≥ DERIVED_MIN_LEN 的秘密做派生——短值的派生形态（如倒序 "ba"）误掩面太大。
        //    与精确值合并后按长度降序一次替换，仍保持「长值优先」。
        val literals = (exactValues + exactValues.flatMap { derivedForms(it) })
            .distinct()
            .sortedByDescending { it.length }
        literals.forEach { secret -> out = out.replace(secret, MASK) }
        // 2c) 逐字符分隔形态：字符间插空白/-/_ 等分隔符的绕过（如 "s e c r e t"）。
        //    只对长度适中的秘密编译分隔容忍正则（短值误掩、超长值拖慢掩码）。
        exactValues
            .filter { it.length in DERIVED_MIN_LEN..SEPARATOR_TOLERANT_MAX_LEN }
            .forEach { secret -> out = separatorTolerantRegex(secret).replace(out, MASK) }
        return out
    }

    /** 同一秘密的派生字面形态：base64（标准/URL 安全、带/不带填充）与倒序。短秘密不派生。 */
    internal fun derivedForms(value: String): List<String> {
        if (value.length < DERIVED_MIN_LEN) return emptyList()
        val bytes = value.toByteArray(Charsets.UTF_8)
        val std = Base64.getEncoder().encodeToString(bytes)
        val url = Base64.getUrlEncoder().encodeToString(bytes)
        return listOf(std, std.trimEnd('='), url, url.trimEnd('='), value.reversed())
            .filter { it.length >= 2 }
            .distinct()
    }

    /** 逐字符分隔容忍正则：秘密的字符按序出现，字符间允许空白或 - _ . , : ; 分隔符。 */
    internal fun separatorTolerantRegex(secret: String): Regex {
        val body = secret.map { Regex.escape(it.toString()) }.joinToString(SEPARATOR_CLASS)
        return Regex(body)
    }
}

/** 密钥库全部凭证的掩码规则（随加随掩；复用 refresh 缓存避免重复解密）。内存态，用完即弃。 */
internal suspend fun allVaultSecrets(repository: CredentialVaultRepository): List<SecretRule> {
    SecretMasker.refresh(repository)
    return SecretMasker.cachedRules
}
