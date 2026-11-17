package me.rerere.rikkahub.utils

import android.content.Context

// 图标来源：lobehub 静态包 @lobehub/icons-static-svg@1.103.0（MIT，见 assets/icons/）；
// 另有 3 个库外文件：mazal.svg、scnet.png、open-design.png（分别取自品牌官网）。
// https://lobehub.com/zh/icons

/**
 * 名字归一化：小写、只留字母与数字（去空格与各类分隔符）。
 * 中文名归一化后仍是中文，进不了英文 slug，靠 [CHINESE_ALIASES] 翻译。
 */
internal fun normalizeProviderName(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/**
 * 中文名 → 英文 slug。只覆盖归一化不到英文的中文名；英文名直接走 slug 查找，不进这张表。
 *
 * 查找顺序是“先精确、再最长包含”：长键优先于短键——“讯飞星火”命中 spark 而不是
 * “讯飞”的 iflytekcloud；“火山引擎”命中 volcengine 而不是“豆包”（旧正则里
 * “火山”同时命中豆包与字节，顺序错了就会判错，这个教训保留）。
 * 歧义名（同时含两个别名的中文名）**必须显式登记完整长键**，否则等长时按表序取先者会判错
 * （“腾讯混元”就是这样被判成腾讯的，现已显式登记为 hunyuan）。
 */
private val CHINESE_ALIASES: Map<String, String> = linkedMapOf(
    // ── 登记原则 ─────────────────────────────────────────────
    // 1) 只登记「归一化后仍是中文、进不了英文 slug」的名字；英文名不走这张表。
    // 2) **同族名字必须把完整长键显式登记**：两个别名等长时最长匹配会按表序取先者，
    //    会把模型品牌判成母公司（“腾讯混元”→“腾讯”、“百度文心”→“百度”、“阿里通义”→“阿里”）。
    //    长键优先，母公司作为回退。
    // 3) 每个 slug 都必须能在 assets/icons/ 里查到（<slug>-color.svg / <slug>.svg / <slug>.png），
    //    登记了库里没有的 slug 等于永远回落首字母，反而更难排查。
    // 4) 云端品牌与母品牌分开：阿里云/腾讯云/百度云/华为云各有自己的 slug，不要并到母公司。
    // ────────────────────────────────────────────────────────
    // 阿里系
    "阿里通义" to "qwen",
    "通义千问" to "qwen",
    "通义" to "qwen",
    "千问" to "qwen",
    "阿里云" to "alibabacloud",
    "阿里" to "alibaba",
    // 腾讯系
    "腾讯混元" to "hunyuan",
    "混元" to "hunyuan",
    "腾讯云" to "tencentcloud",
    "腾讯" to "tencent",
    // 百度系
    "百度文心" to "wenxin",
    "文心一言" to "wenxin",
    "文心" to "wenxin",
    "百度云" to "baiducloud",
    "百度" to "baidu",
    // 字节 / 讯飞
    "字节跳动" to "bytedance",
    "字节" to "bytedance",
    "讯飞星火" to "spark",
    "讯飞" to "iflytekcloud",
    "火山方舟" to "volcengine",
    "火山引擎" to "volcengine",
    "豆包" to "doubao",
    // 其他模型厂
    "智谱清言" to "zhipu",
    "智谱" to "zhipu",
    "月之暗面" to "moonshot",
    "深度求索" to "deepseek",
    "百川智能" to "baichuan",
    "百川" to "baichuan",
    "书生浦语" to "internlm",
    "书生" to "internlm",
    "浦语" to "internlm",
    "昆仑万维" to "tiangong",
    "天工" to "tiangong",
    "零一万物" to "zeroone",
    "无问芯穹" to "infinigence",
    "硅基流动" to "siliconflow",
    "秘塔" to "metaso",
    "商汤" to "sensenova",
    "阶跃星辰" to "stepfun",
    "阶跃" to "stepfun",
    "魔搭" to "modelscope",
    "超算互联网" to "scnet",
    // 云 / 平台
    "华为云" to "huaweicloud",
    "华为" to "huawei",
)

private fun resolveAlias(normalized: String): String? {
    CHINESE_ALIASES[normalized]?.let { return it }
    return CHINESE_ALIASES.entries
        .filter { (key, _) -> normalized.contains(key) }
        .maxByOrNull { (key, _) -> key.length }
        ?.value
}

private fun lookupIconFile(slug: String, availableIcons: Set<String>): String? {
    val color = "$slug-color.svg"
    if (color in availableIcons) return color
    val plain = "$slug.svg"
    if (plain in availableIcons) return plain
    val png = "$slug.png"
    if (png in availableIcons) return png
    return null
}

/**
 * 按名找 assets/icons/ 里的品牌图标文件。
 *
 * 查找顺序：别名翻译出的 slug → 归一化全名 → 逐词回退（多词名按词试，
 * 如“Amazon Bedrock”取 bedrock、“Azure OpenAI”取 openai，从后往前试）；
 * 每个 slug 优先 `<slug>-color.svg`，其次 `<slug>.svg`，最后 `<slug>.png`
 * （旧资产里有 7 个 PNG 品牌）。找不到返回 null，调用方回落首字母头像。
 *
 * @param availableIcons assets/icons/ 里的文件名集合（调用方用
 * [listBundledIconNames] 枚举一次后传入；测试传假集合即可）。
 */
fun computeAIIconByName(name: String, availableIcons: Set<String>): String? {
    val normalized = normalizeProviderName(name)
    if (normalized.isEmpty()) return null
    val aliased = resolveAlias(normalized) ?: normalized
    val candidates = buildList {
        add(aliased)
        if (aliased != normalized) add(normalized)
        // 多词名逐词回退：从后往前（“Azure OpenAI”先试 openai，与旧行为一致）；
        // 两字母以下的词（如 AI）跳过，没有品牌只叫两个字母。
        name.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .map { it.lowercase() }
            .asReversed()
            .forEach { if (it != aliased && it != normalized) add(it) }
    }
    for (slug in candidates.distinct()) {
        lookupIconFile(slug, availableIcons)?.let { return it }
    }
    return null
}

/** 列出 assets/icons/ 里的文件名集合；调用方自行缓存结果。 */
fun listBundledIconNames(context: Context): Set<String> =
    runCatching { context.assets.list("icons")?.toSet().orEmpty() }.getOrDefault(emptySet())
