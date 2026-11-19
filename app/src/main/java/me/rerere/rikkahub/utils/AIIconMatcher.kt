package me.rerere.rikkahub.utils

import android.content.Context

// 图标来源：lobehub 静态包 @lobehub/icons-static-svg@1.103.0（MIT，见 assets/icons/，
// 其中已有 vllm-color.svg 等品牌文件）；另有 44 个库外文件（各品牌官网 /
// HuggingFace 组织头像 / Wikimedia / 官网 IR 站取回）：
// mazal.svg、scnet.png、open-design.png、tokenrhythm.png、suixiang.png、
// ackai.png、minicpm.png（OpenBMB/MiniCPM 官方 lockup 裁出的蓝色 M 标）；
// 2026-11-18 补 OpenRouter 小众厂商 18 个（nex-agi、thinkingmachines、kt、
// ricursiveai、motif-technologies、particle-ai、recursive、prism-ml、
// moondream、unbiased、scaledown、writer、thedrummer、anthracite-org、
// sao10k、undi95、gryphe、typesafe）与国内小厂/平台 19 个（modelbest、
// langboat、shengshu、hidream、colossalai、deeplang、metax、enflame、
// cambricon、biren、mthreads、iluvatar、jdcloud、chinamobile、
// chinatelecom、chinaunicom、ucloud、ksyun、paratera）。未补：elorian
// （官方头像近白色，浅色主题下不可见）、mancer（无官方 logo）、恒创云
// （官网只有白色版 logo，favicon 不可达）。
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
    // 百炼是独立产品（有自己的标），须显式登记长键，否则按最长包含会判成“阿里云”
    "阿里云百炼" to "bailian",
    "百炼" to "bailian",
    "阿里云" to "alibabacloud",
    "阿里" to "alibaba",
    // 腾讯系
    "腾讯混元" to "hunyuan",
    // 中英混合名（"腾讯 Hunyuan"）归一化为"腾讯hunyuan"：纯中文键接不住，
    // 会回落到"腾讯"→tencent；混合键走同一条最长包含匹配，长键优先于"腾讯"
    "腾讯hunyuan" to "hunyuan",
    "混元" to "hunyuan",
    "腾讯云" to "tencentcloud",
    "腾讯" to "tencent",
    // 百度系
    "百度文心" to "wenxin",
    "文心一言" to "wenxin",
    "文心" to "wenxin",
    "百度云" to "baiducloud",
    "百度千帆" to "baiducloud",
    "千帆" to "baiducloud",
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
    "面壁智能" to "modelbest",
    "面壁" to "modelbest",
    "澜舟科技" to "langboat",
    "澜舟" to "langboat",
    "生数科技" to "shengshu",
    "生数" to "shengshu",
    "智象未来" to "hidream",
    "智象" to "hidream",
    "潞晨科技" to "colossalai",
    "潞晨云" to "colossalai",
    "潞晨" to "colossalai",
    "深言科技" to "deeplang",
    "深言" to "deeplang",
    "沐曦" to "metax",
    "燧原科技" to "enflame",
    "燧原" to "enflame",
    "寒武纪" to "cambricon",
    "壁仞科技" to "biren",
    "壁仞" to "biren",
    "摩尔线程" to "mthreads",
    "天数智芯" to "iluvatar",
    "天数" to "iluvatar",
    "快手可灵" to "kling",
    "可灵" to "kling",
    "字节即梦" to "jimeng",
    "即梦" to "jimeng",
    "海螺" to "hailuo",
    "硅基流动" to "siliconflow",
    "秘塔" to "metaso",
    "商汤" to "sensenova",
    "阶跃星辰" to "stepfun",
    "阶跃" to "stepfun",
    "七牛云" to "qiniu",
    "七牛" to "qiniu",
    "魔搭" to "modelscope",
    "超算互联网" to "scnet",
    // 云 / 平台
    "华为云" to "huaweicloud",
    "华为" to "huawei",
    "京东云" to "jdcloud",
    "京东" to "jdcloud",
    "移动云" to "chinamobile",
    "中国移动" to "chinamobile",
    "电信云" to "chinatelecom",
    "中国电信" to "chinatelecom",
    "联通云" to "chinaunicom",
    "中国联通" to "chinaunicom",
    // 网关
    "随想" to "suixiang",
    "优刻得" to "ucloud",
    "金山云" to "ksyun",
    "并行科技" to "paratera",
    "并行" to "paratera",
)

/**
 * 英文 / 缩写名 → 库里的 slug。与 [CHINESE_ALIASES] 分开：
 * 中文表按「最长包含」匹配（中文没有词边界），英文必须**精确**匹配，
 * 否则 `mimo` 会被 `xiaomimimo` 之类的长名反向吃掉。
 *
 * 只登记「库里确实有、但名字对不上」的：如小米 MiMo 在库里是 `xiaomimimo`，
 * GLM 是智谱的模型品牌（库里 `glmv` 是 GLM-V，通用 GLM 归 `zhipu`）。
 */
private val ENGLISH_ALIASES: Map<String, String> = linkedMapOf(
    "mimo" to "xiaomimimo",
    "glm" to "zhipu",
    "glm4" to "zhipu",
    "glm4v" to "zhipu",
    "chatglm" to "zhipu",
    "agens" to "agnesai",
    "agnes" to "agnesai",
    "zai" to "zhipu",
    // 内置提供商用英文名（"Volcano Engine"），中文表覆盖不到，需在英文表登记
    "volcanoengine" to "volcengine",
    "alibabacloudbailian" to "bailian",
    "tencenthunyuan" to "hunyuan",
    "moonshotai" to "moonshot",
    // grok-* 走**模型家族表**（"grok" 不在本表里，否则自映射会短路掉 lookupIconFile 的回退）。
    // 库里 grok.svg 是产品标、xai.svg 是公司 X 形标；模型列表要显示产品标。
    "kimi" to "moonshot",
    // 万相（Wan）是阿里通义的生图模型；库里没有独立 wan 图标，归阿里
    "wan" to "alibaba",
    "ernie" to "wenxin",
    // 国内小厂 / 平台英文名（2026-11-18 补）：01.AI、商汤英文名、百度千帆、
    // 生数新品牌 Genspi、潞晨科技英文名、摩尔线程、京东、三家运营商云、
    // 金山云英文名、天数智芯英文品牌
    "01ai" to "zeroone",
    "sensetime" to "sensenova",
    "qianfan" to "baiducloud",
    "genspi" to "shengshu",
    "luchentech" to "colossalai",
    "moorethreads" to "mthreads",
    "jd" to "jdcloud",
    "chinamobilecloud" to "chinamobile",
    "chinatelecomcloud" to "chinatelecom",
    "kingsoftcloud" to "ksyun",
    "iluvatarcorex" to "iluvatar",
    // ── 模型名里常见的品牌缩写（与提供商名共用一张表，模型解析见
    // [computeModelIconByName]）──
    // gpt-* 是 OpenAI 的模型名前缀（库里只有 openai.svg，没有 gpt.svg）
    "gpt" to "openai",
    // hy3/hy4 是腾讯混元的模型名前缀（Hy4 官方名，见 hy.tencent.com）
    "hy" to "hunyuan",
    // seed-* 是字节豆包的模型名前缀（Doubao-Seed-2.1，火山方舟）
    "seed" to "doubao",
    // step-* 是阶跃星辰的模型名前缀
    "step" to "stepfun",
    // neohorse-* 是基元律动的模型名前缀（TokenRhythm/NeoHorse，库里只有 tokenrhythm.png）
    "neohorse" to "tokenrhythm",
    // d3/d3-flash 是 vLLM Semantic Router 的决策模型（vllm-sr/d3，见 AMD Radeon Cloud
    // 模型目录）；不是 DeepSeek——名字里没有 deepseek，端点也在 developer.amd.com.cn
    "d3" to "vllm",
)

private fun resolveAlias(normalized: String): String? {
    CHINESE_ALIASES[normalized]?.let { return it }
    return CHINESE_ALIASES.entries
        .filter { (key, _) -> normalized.contains(key) }
        .maxByOrNull { (key, _) -> key.length }
        ?.value
}

/**
 * 模型家族前缀 → 品牌 slug。
 *
 * 模型 ID 常写成 `gpt-5.4`、`Qwen3-235B-A22B`、`claude-sonnet-5`、`Intern-S1`——
 * 它们是**模型名不是品牌名**，直接查 slug 查不到（`gpt54.svg` 不存在），
 * 于是整排模型退回首字母。这张表把家族前缀映射回品牌。
 *
 * 匹配按**长度降序**（`stepfun` 先于 `step`、`gemini` 先于 `gem`），
 * 只做前缀匹配、不做任意位置包含（避免 `xiyansql-qwencoder` 里的 `qwen` 误判成 Qwen 官方）。
 */
private val MODEL_FAMILY_ALIASES: List<Pair<String, String>> = listOf(
    "chatgpt" to "openai",
    "gpt" to "openai",
    "o1-preview" to "openai",
    // claude-* 用 Claude 自己的标（库里 claude-color.svg），不是 Anthropic 公司标：
    // 模型列表里一排 claude-* 全显示公司的 A 形标，既分不出系列也认不出是 Claude。
    "grok" to "grok",
    "muse" to "muse",
    "claude" to "claude",
    "gemini" to "gemini",
    "gemma" to "gemma",
    "qwen" to "qwen",
    "qwq" to "qwen",
    "llama" to "meta",
    "intern" to "internlm",
    "glm" to "zhipu",
    "kimi" to "moonshot",
    "moonshot" to "moonshot",
    "deepseek" to "deepseek",
    "stepfun" to "stepfun",
    "minimax" to "minimax",
    "mimo" to "xiaomimimo",
    "agnes" to "agnesai",
    "phi" to "microsoft",
    "mixtral" to "mistral",
    "mistral" to "mistral",
    "command" to "cohere",
    "doubao" to "doubao",
    "hunyuan" to "hunyuan",
    "ernie" to "wenxin",
    "baichuan" to "baichuan",
    "spark" to "spark",
    // ── 模型品牌键：模型列表里这些比公司名更具体 ──
    "hy" to "hunyuan",       // hy3/hy4（腾讯混元官方模型名）
    "codex" to "codex",      // gpt-5.3-codex 用 Codex 自己的标
    "seed" to "doubao",      // Doubao-Seed
    "step" to "stepfun",     // Step-3.7
    "longcat" to "longcat",
    "minicpm" to "minicpm",
    "d3" to "vllm",          // vLLM Semantic Router 的决策模型
    "vllm" to "vllm",
).sortedByDescending { it.first.length }

/**
 * 从模型 ID 推断品牌候选：先看 vendor 段（`meta/muse-spark` → `meta`），
 * 再对每段做家族前缀匹配（`Intern-S1` → `internlm`、`gpt-5.4` → `openai`）。
 */
private fun modelFamilyCandidates(name: String): List<String> {
    // ⚠ vendor 段**只认 `/`**：不能把空格/管道当分隔符，否则 `Azure OpenAI`、
    // `Tencent Hunyuan`、`Groq Llama` 这类**带空格的品牌名**会被当成
    // “vendor=azure / vendor=tencent”，整排图标串到错的品牌上。
    val segments = name
        .split('/')
        .map { seg -> seg.filter { it.isLetterOrDigit() }.lowercase() }
        .filter { it.isNotEmpty() }
    if (segments.isEmpty()) return emptyList()
    // 段内再切词：`XiYanSQL-QwenCoder-32B` 的品牌在中间那个词里，
    // 只对整段做前缀匹配会漏。切词后每个词都试一次前缀匹配。
    // ⚠ 必须从**原始 name** 切词：segments 已经过 normalizeProviderName（分隔符被吃掉），
    // 在它上面再 split 切不出 `QwenCoder` 这类中间词。
    val tokens = name
        .split('/', '-', '_', '.', ':', '@', ' ', ',')
        .map { seg -> seg.filter { it.isLetterOrDigit() }.lowercase() }
        .filter { it.isNotEmpty() }

    val out = mutableListOf<String>()
    // ① **模型家族词优先于 vendor 段**。模型列表里区分的是「哪个模型」：
    //    `google/gemini-*` 该显示 Gemini 而不是 Google G；
    //    `tencent/hy3`/`hy4`/`hy4-flash` 该显示混元而不是一排相同的腾讯 T。
    //    （提供商列表是另一条路径 computeAIIconByName，仍按公司品牌走。）
    // 子品牌词优先（`muse-spark` 先试 muse 再试 spark；`gpt-5.3-codex` 先试 codex）
    for (token in tokens.sortedBy { t -> if (t in MODEL_SUBBRAND_WORDS) 0 else 1 }) {
        MODEL_FAMILY_ALIASES.firstOrNull { token.startsWith(it.first) }?.let { out.add(it.second) }
    }
    // ② vendor 段兜底：模型名里没有已知品牌词时（如 `poolside/laguna`、`xai/grok` 的 xai）
    if (segments.size >= 2) out.add(segments.first())
    return out.distinct()
}

private fun lookupIconFile(slug: String, availableIcons: Set<String>): String? {
    val color = "$slug-color.svg"
    if (color in availableIcons) return color
    val plain = "$slug.svg"
    if (plain in availableIcons) return plain
    val png = "$slug.png"
    if (png in availableIcons) return png
    // 回退：文件名自身可能带分隔符（`open-design.png`、`ai21-brand.svg`），而 slug 在
    // normalizeProviderName 里已经把分隔符去掉了 → 逐级把文件名也归一化再比一次。
    // 必须放在精确匹配之后，避免 `foo-bar` 抢在 `foobar` 前面。
    val target = slug.filter { it.isLetterOrDigit() }
    if (target.isEmpty()) return null
    for (suffix in listOf("-color.svg", ".svg", ".png")) {
        val hit = availableIcons.firstOrNull { f ->
            f.endsWith(suffix) &&
                f.removeSuffix(suffix).filter { it.isLetterOrDigit() }.equals(target, ignoreCase = true)
        }
        if (hit != null) return hit
    }
    return null
}

/**
 * 按名找 assets/icons/ 里的品牌图标文件。
 *
 * 查找顺序：模型家族前缀 → 别名翻译出的 slug → 归一化全名 → 逐词回退（多词名按词试，
 * 如“Amazon Bedrock”取 bedrock、“Azure OpenAI”取 openai，从后往前试）→
 * 端点域名品牌。命中即返回，不做二次覆盖。
 * 两层判据：名字层是用户显式写明的品牌，优先；端点域名是兜底，只救“名字完全没有
 * 品牌词”的短名——域名里的词常不是提供商自己的品牌（同一端点可能服务多个产品线，
 * 按端点取值会把显式写明的品牌判错）。
 * 每个 slug 优先 `<slug>-color.svg`，其次 `<slug>.svg`，最后 `<slug>.png`
 * （旧资产里有 7 个 PNG 品牌）。找不到返回 null，调用方回落首字母头像。
 *
 * @param availableIcons assets/icons/ 里的文件名集合（调用方用
 * [listBundledIconNames] 枚举一次后传入；测试传假集合即可）。
 */
fun computeAIIconByName(
    name: String,
    availableIcons: Set<String>,
    baseUrl: String? = null,
): String? {
    val normalized = normalizeProviderName(name)
    if (normalized.isEmpty()) return null
    val aliased = resolveAlias(normalized) ?: normalized
    val candidates = buildList {
        // 模型家族：模型 ID 不是品牌名，靠 vendor 段 / 家族前缀反推
        modelFamilyCandidates(name).forEach { add(it) }
        add(aliased)
        if (aliased != normalized) add(normalized)
        // 多词名逐词回退：从后往前（“Azure OpenAI”先试 openai，与旧行为一致）；
        // 两字母以下的词（如 AI）跳过，没有品牌只叫两个字母。
        name.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .map { it.lowercase() }
            .asReversed()
            .forEach { if (it != aliased && it != normalized) add(it) }
        // 端点兜底：名字层完全没有品牌词时才用域名里的品牌（无品牌含义的短名靠这一层）。
        hostBrandCandidates(baseUrl).forEach { raw ->
            val flat = raw.filter { ch -> ch.isLetterOrDigit() }
            add(HOST_ALIASES[flat] ?: flat)
            add(flat)
        }
    }
    for (slug in candidates.distinct()) {
        // 英文/缩写别名（精确匹配）优先，再回原 slug
        val mapped = ENGLISH_ALIASES[slug]
        if (mapped != null && mapped != slug) {
            lookupIconFile(mapped, availableIcons)?.let { return it }
        }
        lookupIconFile(slug, availableIcons)?.let { return it }
    }
    return null
}

/**
 * 去掉词尾的版本号后缀：`qwen3` → `qwen`、`hy4` → `hy`、`gpt6` → `gpt`、
 * `deepseekv3` → `deepseek`、`minicpm5` → `minicpm`。剥掉后少于 2 个字符
 * 不算（`k3` → `k`、`v2` → `` 都没有品牌意义）；不以数字结尾的词原样返回 null。
 */
private fun stripVersionSuffix(word: String): String? {
    val stripped = word.replace(Regex("^(.*?)v?\\d+$"), "$1")
    if (stripped == word || stripped.length < 2) return null
    return stripped
}

/**
 * 按模型名找 assets/icons/ 里的品牌图标文件。
 *
 * 与提供商名解析（[computeAIIconByName]）的区别：模型 ID 的品牌词在**前面**
 *（`gpt-6-luna` → gpt、`qwen3.8-max` → qwen、`muse-spark-1.2` → muse），
 *而提供商名是「品牌在后」（`Tencent Hunyuan` → hunyuan），所以这里按词从前
 *往后试；再加上版本号剥离（`qwen3` → `qwen`、`hy4` → `hy`）与 2 字母词
 *（`d3` 只有 2 个字母，提供商路径的 ≥3 字母过滤会把它丢掉）。
 *
 * 找不到返回 null，调用方按优先级链回退（提供商名/端点 → 首字母兜底），
 * 不要在这里猜品牌：`llama` 在库里没有 Meta Llama 的文件（只有 llamaindex，
 * 是另一家），宁可回落首字母也不能错挂别人的标。
 *
 * @param availableIcons assets/icons/ 里的文件名集合（与 [computeAIIconByName] 同口径）。
 */
/**
 * 模型子品牌词：比厂商前缀更具体，**必须优先匹配**。
 *
 * `gpt-5.3-codex` 是 OpenAI 的模型，但用户认的是 Codex 那个终端样式图标；
 * `muse-spark` 是 Meta 的模型，但品牌词是 Muse（不优先就会被 `spark` 抢成讯飞星火）。
 * 排序是稳定的：同组内保持原词序。
 */
private val MODEL_SUBBRAND_WORDS: Set<String> = setOf(
    "codex", "muse", "kimi", "longcat", "minicpm", "d3", "qwq", "gemma", "grok",
    "hy", "seed", "step", "gemini", "qwen", "glm", "deepseek", "doubao", "hunyuan",
    "claude", "llama", "mistral", "mixtral", "command", "phi", "ernie", "baichuan",
    "intern", "moonshot", "minimax", "mimo", "agnes", "vllm",
)

fun computeModelIconByName(
    modelId: String,
    availableIcons: Set<String>,
): String? {
    val normalized = normalizeProviderName(modelId)
    if (normalized.isEmpty()) return null
    val candidates = buildList {
        // ① 家族词优先（与提供商侧共用同一条推断）：`tencent/hy4` 显示混元而非腾讯、
        //    `google/gemini-*` 显示 Gemini 而非 Google —— 模型列表里区分的是「哪个模型」。
        addAll(modelFamilyCandidates(modelId))
        add(normalized)
        stripVersionSuffix(normalized)?.let { add(it) }
        // 模型 ID 按分隔符切词，品牌词在前；2 字母词也保留（d3），1 字母词没有品牌意义。
        modelId.split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .map { it.lowercase() }
            // 子品牌词优先：`gpt-5.3-codex` 先试 codex 再试 gpt
            .sortedBy { word -> if (word in MODEL_SUBBRAND_WORDS) 0 else 1 }
            .forEach { word ->
                add(word)
                stripVersionSuffix(word)?.let { add(it) }
            }
    }
    for (slug in candidates.distinct()) {
        // 英文/缩写别名（精确匹配）优先，再回原 slug；与 computeAIIconByName 同口径
        val mapped = ENGLISH_ALIASES[slug]
        if (mapped != null && mapped != slug) {
            lookupIconFile(mapped, availableIcons)?.let { return it }
        }
        lookupIconFile(slug, availableIcons)?.let { return it }
    }
    return null
}

/**
 * 从 API Base URL 里取品牌候选词。
 *
 * 为什么需要：提供商常被起成过短、无品牌含义的自定义短名，名字本身进不了 slug，
 * 但域名里就带着品牌。按域名匹配是这类情况的唯一可靠依据。
 *
 * 规则：取主机名去掉顶级域与常见通用前缀（`api`/`apihub`/`www`…），得到品牌段；
 * 同时给出「多段拼接」与「最后一段」两个候选（`api.foo.example` → `fooexample` / `example`）。
 */
/**
 * 域名品牌段 → 库里 slug。只登记「域名里的词和文件名对不上」的：
 * `open.bigmodel.cn` 是智谱（slug 为 zhipu），`api.agnes-ai.cn` 是 agnesai；
 * 其余品牌域名归一化后本身就能命中，无需登记。
 */
private val HOST_ALIASES: Map<String, String> = linkedMapOf(
    "bigmodel" to "zhipu",
    "zhipuai" to "zhipu",
    "xiaomi" to "xiaomimimo",
    // dashscope.aliyuncs.com 是阿里云百炼平台的 API 域名：域名候选产出 bailian
    // 平台标，而不是 qwen 模型标；Qwen 系模型图标由模型名路径
    // computeModelIconByName 负责，不受影响
    "dashscope" to "bailian",
    "aliyuncs" to "bailian",
    "volces" to "volcengine",
    "volcengineapi" to "volcengine",
    "tencentcloudapi" to "tencentcloud",
    "baidubce" to "baidu",
    "sensetime" to "sensenova",
    "amd" to "amdradeoncloud",
    "minimaxi" to "minimax",
    "tencentmaas" to "hunyuan",
)

internal fun hostBrandCandidates(baseUrl: String?): List<String> {
    if (baseUrl.isNullOrBlank()) return emptyList()
    val host = runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return emptyList()
    val parts = host.split('.').filter { it.isNotBlank() }
    if (parts.size < 2) return emptyList()
    val generic = setOf(
        "www", "api", "apihub", "gateway", "proxy", "cdn", "open", "v1", "v2",
        "com", "net", "org", "ai", "io", "cn", "co", "dev", "app", "studio", "cloud", "tech",
    )
    val brand = parts.dropLast(1).filter { it !in generic && it.isNotBlank() }
    if (brand.isEmpty()) return emptyList()
    return listOf(brand.joinToString(""), brand.last()).distinct()
}

/** 列出 assets/icons/ 里的文件名集合；调用方自行缓存结果。 */
fun listBundledIconNames(context: Context): Set<String> =
    runCatching { context.assets.list("icons")?.toSet().orEmpty() }.getOrDefault(emptySet())
