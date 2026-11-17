package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * computeAIIconByName 回归：通用匹配（归一化 → 别名 → slug 查文件，优先 -color.svg）。
 * availableIcons 用假集合，与真实 assets/icons/ 里的文件名一致。
 */
class AIIconMatcherTest {

    private val icons = setOf(
        "openai.svg", "deepseek-color.svg", "openrouter.svg", "bing.png",
        "huggingface-color.svg", "together-color.svg", "fireworks-color.svg",
        "replicate.svg", "deepinfra-color.svg", "azure-color.svg",
        "bedrock-color.svg", "coze.svg", "dify-color.svg", "mazal.svg",
        "sensenova-color.svg", "modelscope-color.svg", "iflytekcloud-color.svg",
        "spark-color.svg", "baidu-color.svg", "tencent-color.svg",
        "volcengine-color.svg", "wenxin-color.svg", "doubao-color.svg",
        "hunyuan-color.svg", "stepfun-color.svg", "scnet.png",
        "opencode.svg", "agnesai.svg", "commandcode.svg", "manus.svg",
        "cursor.svg", "kiro-color.svg", "lovable-color.svg", "windsurf.svg",
        "copilot-color.svg", "open-design.png", "muse-color.svg",
        // 中文别名表覆盖到的其余品牌
        "qwen-color.svg", "alibabacloud-color.svg", "alibaba-color.svg",
        "tencentcloud-color.svg", "baiducloud-color.svg", "huaweicloud-color.svg",
        "huawei-color.svg", "zhipu-color.svg", "moonshot.svg",
        "baichuan-color.svg", "internlm-color.svg", "tiangong-color.svg",
        "zeroone-color.svg", "infinigence-color.svg", "siliconflow.svg",
        "metaso.svg", "bytedance-color.svg", "minimax-color.svg",
        "xiaomimimo.svg", "open-design.png", "agnesai.svg", "xai.svg", "grok.svg", "glmv-color.svg",
        // 模型家族 / 厂商需要用到的
        "anthropic.svg", "qiniu-color.svg", "poolside-color.svg", "internlm-color.svg",
        "minimax-color.svg", "gemma-color.svg", "meta-color.svg", "google-color.svg",
        "stepfun-color.svg", "zhipu-color.svg",
        "codex-color.svg", "muse-color.svg", "alibaba-color.svg",
        "foo-color.svg", "foo.svg", "bar.svg", "baz.png",
        // 本批新增：端点别名 amd/minimaxi/tencentmaas 与新图标文件
        "amdradeoncloud-color.svg", "tokenrhythm.png", "suixiang.png", "ackai.png",
        // 本批新增：模型解析用到的品牌文件（与真实 assets/icons/ 同名）
        "minicpm.png", "vllm-color.svg", "longcat-color.svg",
        "claude-color.svg", "gemini-color.svg",
        // 本批新增：海螺 / 可灵 / 即梦（真实文件已在库里，中文别名表新登记）
        "hailuo-color.svg", "kling-color.svg", "jimeng-color.svg",
        // 本批新增：OpenRouter 小众厂商官方图标（18 个）
        "nex-agi.png", "thinkingmachines.png", "kt-color.svg",
        "ricursiveai.png", "motif-technologies.png", "particle-ai.png",
        "recursive.png", "prism-ml.png", "moondream.png",
        "unbiased-color.svg", "scaledown.png", "writer.png",
        "thedrummer.png", "anthracite-org.png", "sao10k.png",
        "undi95.png", "gryphe.png", "typesafe.png",
        // 本批新增：国内小厂 / 平台官方图标（19 个）
        "modelbest.png", "langboat.png", "shengshu.png", "hidream.png",
        "colossalai-color.svg", "deeplang.png", "metax.png",
        "enflame-color.svg", "cambricon.png", "biren.png",
        "mthreads.png", "iluvatar.png", "jdcloud.png",
        "chinamobile-color.svg", "chinatelecom.png", "chinaunicom.png",
        "ucloud.png", "ksyun.png", "paratera.png",
    )

    private fun resolve(name: String, baseUrl: String? = null) =
        computeAIIconByName(name, icons, baseUrl)

    private fun resolveModel(modelId: String) =
        computeModelIconByName(modelId, icons)

    @Test
    fun `normalization strips separators and falls back word by word`() {
        assertEquals("openai.svg", resolve("OpenAI"))
        assertEquals("deepseek-color.svg", resolve("  DeepSeek  "))
        assertEquals("huggingface-color.svg", resolve("Hugging-Face"))
        assertEquals("bedrock-color.svg", resolve("Amazon Bedrock"))
        assertEquals("together-color.svg", resolve("Together AI"))
        assertEquals("fireworks-color.svg", resolve("Fireworks AI"))
        assertEquals("openai.svg", resolve("Azure OpenAI"))
        assertEquals("hunyuan-color.svg", resolve("Tencent Hunyuan"))
        assertEquals("mazal.svg", resolve("mazal_imgae"))
    }

    @Test
    fun `color variant wins over plain svg and png`() {
        assertEquals("foo-color.svg", resolve("Foo"))
        assertEquals("bar.svg", resolve("Bar"))
        assertEquals("baz.png", resolve("Baz"))
    }

    @Test
    fun `chinese alias table resolves`() {
        assertEquals("baidu-color.svg", resolve("百度"))
        assertEquals("baidu-color.svg", resolve("百度智能云"))
        assertEquals("tencent-color.svg", resolve("腾讯"))
        assertEquals("tencentcloud-color.svg", resolve("腾讯云"))
        assertEquals("iflytekcloud-color.svg", resolve("讯飞"))
        assertEquals("spark-color.svg", resolve("讯飞星火"))
        assertEquals("wenxin-color.svg", resolve("文心"))
        assertEquals("wenxin-color.svg", resolve("文心一言"))
        assertEquals("volcengine-color.svg", resolve("火山引擎"))
        assertEquals("doubao-color.svg", resolve("豆包"))
        assertEquals("hunyuan-color.svg", resolve("混元"))
        assertEquals("modelscope-color.svg", resolve("魔搭"))
        assertEquals("sensenova-color.svg", resolve("商汤"))
        assertEquals("stepfun-color.svg", resolve("阶跃"))
        assertEquals("scnet.png", resolve("超算互联网"))
    }

    @Test
    fun `long alias wins over short alias`() {
        // “讯飞星火”命中 spark 而不是“讯飞”的 iflytekcloud；
        // “火山引擎”命中 volcengine 而不是“豆包”。
        assertEquals("spark-color.svg", resolve("讯飞星火"))
        assertEquals("volcengine-color.svg", resolve("火山引擎"))
        assertEquals("iflytekcloud-color.svg", resolve("讯飞"))
    }

    @Test
    fun `ambiguous compound name follows table order`() {
        // “腾讯混元”同时含“腾讯”与“混元”，等长 → 已显式登记完整长键，取混元（独立品牌优先）。
        // 判错了走图标覆盖表纠正。
        assertEquals("hunyuan-color.svg", resolve("腾讯混元"))
        // 同类：百度文心 → 文心（不是百度）
        assertEquals("wenxin-color.svg", resolve("百度文心"))
    }


    @Test
    fun `chinese aliases cover major providers precisely`() {
        // 每一条都是「完整品牌名 → 自己的图标」，不是回落到母公司图标
        assertEquals("qwen-color.svg", resolve("阿里通义"))
        assertEquals("qwen-color.svg", resolve("通义千问"))
        assertEquals("qwen-color.svg", resolve("通义"))
        assertEquals("qwen-color.svg", resolve("千问"))
        assertEquals("alibabacloud-color.svg", resolve("阿里云"))
        assertEquals("alibaba-color.svg", resolve("阿里"))
        assertEquals("zhipu-color.svg", resolve("智谱"))
        assertEquals("zhipu-color.svg", resolve("智谱清言"))
        // 注意：库里只有 moonshot.svg（无 -color 版），假集合须与真实文件名一致
        assertEquals("moonshot.svg", resolve("月之暗面"))
        assertEquals("deepseek-color.svg", resolve("深度求索"))
        assertEquals("baichuan-color.svg", resolve("百川智能"))
        assertEquals("internlm-color.svg", resolve("书生浦语"))
        assertEquals("tiangong-color.svg", resolve("昆仑万维"))
        assertEquals("tiangong-color.svg", resolve("天工"))
        assertEquals("zeroone-color.svg", resolve("零一万物"))
        assertEquals("infinigence-color.svg", resolve("无问芯穹"))
        assertEquals("siliconflow.svg", resolve("硅基流动"))
        assertEquals("metaso.svg", resolve("秘塔"))
        assertEquals("bytedance-color.svg", resolve("字节跳动"))
        assertEquals("huaweicloud-color.svg", resolve("华为云"))
        assertEquals("huawei-color.svg", resolve("华为"))
        assertEquals("stepfun-color.svg", resolve("阶跃星辰"))
    }

    @Test
    fun `cloud brands do not fall back to parent brand`() {
        // 云与母品牌各有 slug，不要并到一起（否则“阿里云”显示阿里、“腾讯云”显示腾讯）
        assertEquals("alibabacloud-color.svg", resolve("阿里云"))
        assertEquals("tencentcloud-color.svg", resolve("腾讯云"))
        assertEquals("baiducloud-color.svg", resolve("百度云"))
        assertEquals("huaweicloud-color.svg", resolve("华为云"))
    }

    @Test
    fun `english compound names fall back to the brand word`() {
        // 多词英文名从后往前回退（品牌在后）
        assertEquals("hunyuan-color.svg", resolve("Tencent Hunyuan"))
        assertEquals("qwen-color.svg", resolve("Alibaba Qwen"))
        assertEquals("zhipu-color.svg", resolve("Zhipu AI"))
        assertEquals("moonshot.svg", resolve("Moonshot Kimi"))
        assertEquals("deepseek-color.svg", resolve("DeepSeek V3"))
        assertEquals("minimax-color.svg", resolve("MiniMax ABAB"))
    }

    @Test
    fun `english abbreviations and dashed filenames resolve`() {
        // 小米 MiMo 在库里是 xiaomimimo（不是 mimo）
        assertEquals("xiaomimimo.svg", resolve("MIMO"))
        assertEquals("xiaomimimo.svg", resolve("MiMo"))
        // GLM 系列归智谱
        assertEquals("zhipu-color.svg", resolve("GLM"))
        assertEquals("zhipu-color.svg", resolve("GLM-4V-Flash"))
        assertEquals("zhipu-color.svg", resolve("ChatGLM"))
        // 文件名自带连字符（open-design.png）：归一化会去掉分隔符，需要归一化回退
        assertEquals("open-design.png", resolve("Open Design"))
        assertEquals("open-design.png", resolve("open-design"))
        // 拼写差异（域名是 agnes-ai.com，库里是 agnesai）
        assertEquals("agnesai.svg", resolve("agens"))
    }

    @Test
    fun `endpoint host wins over custom name`() {
        // 名字是自定义短名时，端点才是可靠依据
        assertEquals("commandcode.svg", resolve("CC", "https://api.commandcode.ai/provider/v1"))
        assertEquals("opencode.svg", resolve("Zen", "https://opencode.ai/zen/v1"))
        assertEquals("zhipu-color.svg", resolve("GLM-4V-Flash", "https://open.bigmodel.cn/api/paas/v4"))
        assertEquals("agnesai.svg", resolve("AGENS_CN", "https://api.agnes-ai.cn/v1"))
        assertEquals("open-design.png", resolve("Open Design", "https://amr-link.open-design.ai/v1"))
        assertEquals("xiaomimimo.svg", resolve("MIMO", "https://api.xiaomimimo.com/v1"))
        // 端点识别不出来时仍按名字
        assertEquals("deepseek-color.svg", resolve("DeepSeek", "https://api.deepseek.com/v1"))
        // 本批补的域名别名：域名里的词与库里文件名对不上，须经 HOST_ALIASES 翻译
        // developer.amd.com.cn → amd → amdradeoncloud（库里只有 amdradeoncloud，没有 amd）
        assertEquals("amdradeoncloud-color.svg", resolve("AMD", "https://developer.amd.com.cn/radeon/api/v1"))
        // api.minimaxi.com → minimaxi → minimax（名字 MiniMax 本身也能解析，用短名隔离端点路径）
        assertEquals("minimax-color.svg", resolve("MM", "https://api.minimaxi.com/anthropic/v1"))
        assertEquals("minimax-color.svg", resolve("MiniMax", "https://api.minimaxi.com/anthropic/v1"))
        // tokenhub.tencentmaas.com → tencentmaas → hunyuan
        assertEquals("hunyuan-color.svg", resolve("TH", "https://tokenhub.tencentmaas.com/v1"))
        assertEquals("hunyuan-color.svg", resolve("Tencent Hunyuan", "https://tokenhub.tencentmaas.com/v1"))
        // 本批新补的图标文件：域名直接产出 slug，不经别名表
        assertEquals("tokenrhythm.png", resolve("基元律动", "https://tokenrhythm.studio/v1"))
        assertEquals("tokenrhythm.png", resolve("TR", "https://tokenrhythm.studio/v1"))
        assertEquals("suixiang.png", resolve("SuiXiang AI Gateway", "https://sui-xiang.com/v1"))
        assertEquals("ackai.png", resolve("AckAI", "https://ackai.fun/v1"))
        assertEquals("ackai.png", resolve("A", "https://ackai.fun/v1"))
    }

    @Test
    fun `model ids resolve to their family brand`() {
        // 模型 ID 不是品牌名：靠 vendor 段或家族前缀反推
        assertEquals("openai.svg", resolve("gpt-5.4"))
        assertEquals("openai.svg", resolve("gpt-5.6-luna"))
        assertEquals("claude-color.svg", resolve("claude-sonnet-5"))
        assertEquals("gemma-color.svg", resolve("Gemma-4-31B-IT"))
        assertEquals("qwen-color.svg", resolve("Qwen3-235B-A22B"))
        assertEquals("minimax-color.svg", resolve("MiniMax-M3"))
        assertEquals("xiaomimimo.svg", resolve("MiMo-V2.6-Flash"))
        assertEquals("agnesai.svg", resolve("agnes-2.5-pro-beta"))
        assertEquals("internlm-color.svg", resolve("Intern-S1"))
    }

    @Test
    fun `vendor segment wins for namespaced model ids`() {
        // 常见形态 `vendor/model`：vendor 段就是品牌
        assertEquals("gemini-color.svg", resolve("google/gemini-4-argon"))
        assertEquals("deepseek-color.svg", resolve("deepseek/deepseek-v4.1-flash"))
        // 模型品牌词优先于 vendor 段：muse 比 meta 更具体
        assertEquals("muse-color.svg", resolve("meta/muse-spark-1.3-contributor"))
        assertEquals("qwen-color.svg", resolve("qwen/qwen3.5-plus"))
        assertEquals("zhipu-color.svg", resolve("z-ai/glm-5.3-flash"))
        assertEquals("zhipu-color.svg", resolve("zai-org/GLM-5.2-Fast"))
        assertEquals("stepfun-color.svg", resolve("stepfun/Step-3.7-Flash"))
        assertEquals("internlm-color.svg", resolve("Shanghai_AI_Laboratory/Intern-S1"))
        assertEquals("qiniu-color.svg", resolve("qiniu/conatus-free"))
    }

    @Test
    fun `qiniu chinese name resolves`() {
        assertEquals("qiniu-color.svg", resolve("七牛云"))
        assertEquals("qiniu-color.svg", resolve("七牛"))
    }

    @Test
    fun `model subbrand wins over vendor prefix`() {
        // gpt-5.3-codex 是 OpenAI 的模型，但图标要显示 Codex
        assertEquals("codex-color.svg", resolveModel("gpt-5.3-codex"))
        // muse-spark 是 Meta 的模型，品牌词是 Muse（不能被 spark 抢成讯飞星火）
        assertEquals("muse-color.svg", resolveModel("meta/muse-spark-1.3-contributor"))
        // 万相是阿里通义的生图模型
        assertEquals("alibaba-color.svg", resolveModel("wan2.7-image"))
        assertEquals("alibaba-color.svg", resolveModel("wan2.7-image-pro"))
    }

    @Test
    fun `no match falls back to null`() {
        assertNull(resolve("some-provider-that-does-not-exist"))
        assertNull(resolve(""))
        assertNull(resolve("   "))
        // 裸“火山”仍不归属（旧正则把“火山”判给豆包，属误判）；
        // 完整品牌名“火山方舟”/“火山引擎”已显式登记到 volcengine。
        assertNull(resolve("火山"))
        // GLM 是智谱的模型品牌，已登记（库里 glmv 是 GLM-V，通用 GLM 归 zhipu）
        assertEquals("zhipu-color.svg", resolve("GLM"))
        // llama 是 Meta 的模型家族 → 归 meta（模型家族识别）
        assertEquals("meta-color.svg", resolve("llama"))
        assertNull(resolve("o1"))
        // UnifyLLM：站上是通用 new-api 前端，没有自有品牌 logo，favicon 是网关软件的
        // 默认标记而非品牌方标记，本批不收；域名与名字都解析不到，仍回落首字母
        assertNull(resolve("UnifyLLM", "https://apicn.unifyllm.top/v1"))
    }

    @Test
    fun `existing brands unchanged`() {
        assertEquals("openai.svg", resolve("OpenAI"))
        assertEquals("deepseek-color.svg", resolve("DeepSeek"))
        assertEquals("openrouter.svg", resolve("OpenRouter"))
        assertEquals("bing.png", resolve("Bing"))
        assertEquals("muse-color.svg", resolve("Muse"))
        assertEquals("copilot-color.svg", resolve("GitHub Copilot"))
    }

    @Test
    fun `model ids from screenshots resolve to brand icons`() {
        // 用户截图里的模型：品牌词在前，从前往后试
        assertEquals("openai.svg", resolveModel("gpt-6-luna"))
        assertEquals("openai.svg", resolveModel("gpt-5.6"))
        assertEquals("qwen-color.svg", resolveModel("qwen3-235b-a22b"))
        assertEquals("qwen-color.svg", resolveModel("qwen3.8-max"))
        assertEquals("moonshot.svg", resolveModel("kimi-k3"))
        assertEquals("moonshot.svg", resolveModel("kimi-k2.7-code"))
        assertEquals("minimax-color.svg", resolveModel("minimax-m3.1-flash"))
        assertEquals("xiaomimimo.svg", resolveModel("mimo-v2.6-flash"))
        assertEquals("xiaomimimo.svg", resolveModel("mimo-v2-pro"))
        assertEquals("doubao-color.svg", resolveModel("seed-2.1-pro"))
        assertEquals("doubao-color.svg", resolveModel("seed-2.1-turbo"))
        assertEquals("hunyuan-color.svg", resolveModel("hy3"))
        assertEquals("hunyuan-color.svg", resolveModel("hy4"))
        assertEquals("hunyuan-color.svg", resolveModel("hy4-flash-free"))
        // 模型家族词优先于组织名：tencent/hy4 显示混元（与裸 hy4 一致）
        // 不一致，是已知取舍：按词从前往后试，首词 tencent 在库里有文件就先命中；
        // 想改成混元标要么加组织词黑名单（误伤面不可控），要么等提供商兜底——
        // 但模型名已经解析到了，兜底走不到。提供商是「腾讯混元」时仍是混元标。
        assertEquals("hunyuan-color.svg", resolveModel("tencent/hy4-preview"))
        assertEquals("stepfun-color.svg", resolveModel("step-5-preview"))
        // grok-* 显示 Grok 产品标（库里 grok.svg），不是 xAI 的公司 X 形标
        assertEquals("grok.svg", resolveModel("grok-4.7"))
        assertEquals("longcat-color.svg", resolveModel("longcat-2.0"))
        assertEquals("tokenrhythm.png", resolveModel("neohorse-1-9b"))
        assertEquals("tokenrhythm.png", resolveModel("neohorse-1.5-flash-preview"))
        assertEquals("vllm-color.svg", resolveModel("d3"))
        assertEquals("vllm-color.svg", resolveModel("d3-flash"))
        assertEquals("minicpm.png", resolveModel("MiniCPM-V-4.7-1B"))
        assertEquals("minicpm.png", resolveModel("MiniCPM5-2B"))
        // muse-spark：品牌词 muse 在前，不许错挂讯飞星火的 spark 标
        assertEquals("muse-color.svg", resolveModel("muse-spark-1.2-contributor"))
        assertEquals("muse-color.svg", resolveModel("muse-spark-1.3"))
    }

    @Test
    fun `model ids generalize across families`() {
        assertEquals("deepseek-color.svg", resolveModel("deepseek-chat"))
        assertEquals("deepseek-color.svg", resolveModel("deepseek-v3.2"))
        assertEquals("deepseek-color.svg", resolveModel("deepseek-reasoner"))
        assertEquals("claude-color.svg", resolveModel("claude-opus-4.6"))
        assertEquals("claude-color.svg", resolveModel("claude-sonnet-4.5"))
        assertEquals("gemini-color.svg", resolveModel("gemini-3-pro"))
        assertEquals("gemini-color.svg", resolveModel("gemini-2.5-flash"))
        assertEquals("zhipu-color.svg", resolveModel("glm-5.3-flash"))
        assertEquals("zhipu-color.svg", resolveModel("glm-4.6"))
        assertEquals("wenxin-color.svg", resolveModel("ernie-5.0"))
        assertEquals("hunyuan-color.svg", resolveModel("hunyuan-turbos-latest"))
        // llama 在库里没有 Llama 专属文件（只有 llamaindex，是另一家），
        // 但 Meta 是它的厂商 → 退到公司标（比首字母有信息量，且不是错挂别家）
        assertEquals("meta-color.svg", resolveModel("llama-4-scout"))
        assertEquals("meta-color.svg", resolveModel("llama"))
        assertNull(resolveModel("o1"))
        assertNull(resolveModel("some-model-that-does-not-exist"))
        assertNull(resolveModel(""))
    }

    @Test
    fun `model version suffix is stripped`() {
        assertEquals("qwen-color.svg", resolveModel("qwen3"))
        assertEquals("qwen-color.svg", resolveModel("qwen2.5-72b"))
        assertEquals("deepseek-color.svg", resolveModel("deepseekv3"))
        assertEquals("minicpm.png", resolveModel("minicpm5"))
        assertEquals("hunyuan-color.svg", resolveModel("hy4"))
        // 剥掉后少于 2 个字符不算品牌
        assertNull(resolveModel("k3"))
        assertNull(resolveModel("v2"))
    }

    @Test
    fun `or niche vendors resolve by vendor segment`() {
        // OpenRouter 目录里 36 个解析不到图标的模型：补厂商自己的官方图标后，
        // vendor 段就是品牌（带分隔符的文件名靠归一化回退命中）
        assertEquals("nex-agi.png", resolveModel("nex-agi/nex-n2.6"))
        assertEquals("nex-agi.png", resolveModel("nex-agi/nex-n2.5-mini"))
        assertEquals("nex-agi.png", resolveModel("nex-agi/nex-n2.5-pro"))
        assertEquals("thinkingmachines.png", resolveModel("thinkingmachines/inkling-2-small"))
        assertEquals("thinkingmachines.png", resolveModel("thinkingmachines/inkling-2-small:free"))
        assertEquals("thinkingmachines.png", resolveModel("thinkingmachines/inkling-small"))
        assertEquals("thinkingmachines.png", resolveModel("thinkingmachines/inkling-small:batch"))
        assertEquals("thinkingmachines.png", resolveModel("thinkingmachines/inkling"))
        assertEquals("kt-color.svg", resolveModel("kt/midm-k-3.0-pro"))
        assertEquals("ricursiveai.png", resolveModel("ricursiveai/ripple-65b-preview"))
        assertEquals("motif-technologies.png", resolveModel("motif-technologies/motif-3"))
        assertEquals("particle-ai.png", resolveModel("particle-ai/particle-1"))
        assertEquals("particle-ai.png", resolveModel("particle-ai/particle-1:free"))
        assertEquals("recursive.png", resolveModel("recursive/flint-v1.0:free"))
        assertEquals("prism-ml.png", resolveModel("prism-ml/ternary-bonsai-2-9b"))
        assertEquals("prism-ml.png", resolveModel("prism-ml/ternary-bonsai-2-27b"))
        assertEquals("moondream.png", resolveModel("moondream/moondream3-preview"))
        assertEquals("moondream.png", resolveModel("moondream/moondream3.1-9b-a2b"))
        assertEquals("unbiased-color.svg", resolveModel("unbiased/pareto-26.10"))
        assertEquals("unbiased-color.svg", resolveModel("unbiased/pareto-latest"))
        assertEquals("unbiased-color.svg", resolveModel("unbiased/pareto-26.9"))
        assertEquals("unbiased-color.svg", resolveModel("unbiased/pareto-26.10-preview"))
        assertEquals("scaledown.png", resolveModel("scaledown/summarize"))
        assertEquals("scaledown.png", resolveModel("scaledown/extract"))
        assertEquals("writer.png", resolveModel("writer/palmyra-x5"))
        assertEquals("thedrummer.png", resolveModel("thedrummer/cydonia-24b-v4.1"))
        assertEquals("thedrummer.png", resolveModel("thedrummer/skyfall-36b-v2"))
        assertEquals("thedrummer.png", resolveModel("thedrummer/unslopnemo-12b"))
        assertEquals("anthracite-org.png", resolveModel("anthracite-org/magnum-v4-72b"))
        assertEquals("sao10k.png", resolveModel("sao10k/l3.1-euryale-70b"))
        assertEquals("sao10k.png", resolveModel("sao10k/l3-lunaris-8b"))
        assertEquals("undi95.png", resolveModel("undi95/remm-slerp-l2-13b"))
        assertEquals("gryphe.png", resolveModel("gryphe/mythomax-l2-13b"))
        assertEquals("typesafe.png", resolveModel("typesafe/jev-router"))
        // 未补的两家仍回落（与说明里的未补原因一一对应）：
        // elorian 官方头像近白色、浅色主题下不可见；mancer 无官方 logo
        assertNull(resolveModel("elorian/glimpse"))
        assertNull(resolveModel("mancer/weaver"))
    }

    @Test
    fun `chinese small vendors and platforms resolve`() {
        // 国内小厂：中文名走中文别名表，英文名直接命中或走英文别名表
        assertEquals("modelbest.png", resolve("面壁智能"))
        assertEquals("modelbest.png", resolve("面壁"))
        assertEquals("modelbest.png", resolve("ModelBest"))
        assertEquals("langboat.png", resolve("澜舟科技"))
        assertEquals("langboat.png", resolve("澜舟"))
        assertEquals("langboat.png", resolve("Langboat"))
        assertEquals("shengshu.png", resolve("生数科技"))
        assertEquals("shengshu.png", resolve("生数"))
        assertEquals("shengshu.png", resolve("Genspi"))
        assertEquals("hidream.png", resolve("智象未来"))
        assertEquals("hidream.png", resolve("智象"))
        assertEquals("hidream.png", resolve("HiDream"))
        assertEquals("colossalai-color.svg", resolve("潞晨科技"))
        assertEquals("colossalai-color.svg", resolve("潞晨云"))
        assertEquals("colossalai-color.svg", resolve("潞晨"))
        assertEquals("colossalai-color.svg", resolve("Colossal-AI"))
        assertEquals("colossalai-color.svg", resolve("LuchenTech"))
        assertEquals("deeplang.png", resolve("深言科技"))
        assertEquals("deeplang.png", resolve("深言"))
        assertEquals("deeplang.png", resolve("DeepLang"))
        assertEquals("metax.png", resolve("沐曦"))
        assertEquals("metax.png", resolve("MetaX"))
        assertEquals("enflame-color.svg", resolve("燧原科技"))
        assertEquals("enflame-color.svg", resolve("燧原"))
        assertEquals("enflame-color.svg", resolve("Enflame"))
        assertEquals("cambricon.png", resolve("寒武纪"))
        assertEquals("cambricon.png", resolve("Cambricon"))
        assertEquals("biren.png", resolve("壁仞科技"))
        assertEquals("biren.png", resolve("壁仞"))
        assertEquals("biren.png", resolve("Biren"))
        assertEquals("mthreads.png", resolve("摩尔线程"))
        assertEquals("mthreads.png", resolve("Moore Threads"))
        assertEquals("iluvatar.png", resolve("天数智芯"))
        assertEquals("iluvatar.png", resolve("天数"))
        assertEquals("iluvatar.png", resolve("Iluvatar"))
        assertEquals("iluvatar.png", resolve("Iluvatar CoreX"))
        assertEquals("kling-color.svg", resolve("快手可灵"))
        assertEquals("kling-color.svg", resolve("可灵"))
        assertEquals("jimeng-color.svg", resolve("字节即梦"))
        assertEquals("jimeng-color.svg", resolve("即梦"))
        assertEquals("hailuo-color.svg", resolve("海螺"))
        // 已在库里的国内厂商，英文名与别名补齐
        assertEquals("zeroone-color.svg", resolve("01.AI"))
        assertEquals("sensenova-color.svg", resolve("SenseTime"))
        assertEquals("baiducloud-color.svg", resolve("百度千帆"))
        assertEquals("baiducloud-color.svg", resolve("千帆"))
        assertEquals("baiducloud-color.svg", resolve("Qianfan"))
        // 推理 / 聚合平台：云与母品牌分开，不回落到母公司标
        assertEquals("jdcloud.png", resolve("京东云"))
        assertEquals("jdcloud.png", resolve("京东"))
        assertEquals("jdcloud.png", resolve("JD Cloud"))
        assertEquals("jdcloud.png", resolve("JD"))
        assertEquals("chinamobile-color.svg", resolve("移动云"))
        assertEquals("chinamobile-color.svg", resolve("中国移动"))
        assertEquals("chinamobile-color.svg", resolve("China Mobile"))
        assertEquals("chinamobile-color.svg", resolve("China Mobile Cloud"))
        assertEquals("chinatelecom.png", resolve("电信云"))
        assertEquals("chinatelecom.png", resolve("中国电信"))
        assertEquals("chinatelecom.png", resolve("China Telecom"))
        assertEquals("chinatelecom.png", resolve("China Telecom Cloud"))
        assertEquals("chinaunicom.png", resolve("联通云"))
        assertEquals("chinaunicom.png", resolve("中国联通"))
        assertEquals("chinaunicom.png", resolve("China Unicom"))
        assertEquals("ucloud.png", resolve("优刻得"))
        assertEquals("ucloud.png", resolve("UCloud"))
        assertEquals("ksyun.png", resolve("金山云"))
        assertEquals("ksyun.png", resolve("Kingsoft Cloud"))
        assertEquals("paratera.png", resolve("并行科技"))
        assertEquals("paratera.png", resolve("并行"))
        assertEquals("paratera.png", resolve("Paratera"))
        // 未补的恒创云仍回落：官网只有白色版 logo，favicon 不可达
        assertNull(resolve("恒创云"))
        assertNull(resolve("Henghost"))
    }

    @Test
    fun `model resolver prefers the first brand word`() {
        // 提供商路径从后往前回退会把 muse-spark 判成 spark（讯飞星火）；
        // 模型路径品牌词在前，判 muse。提供商侧行为本身不动：
        assertEquals("muse-color.svg", resolveModel("muse-spark-1.2-contributor"))
        // 提供商侧也走家族推断后，`Muse Spark` 同样判 muse（与模型侧一致）
        assertEquals("muse-color.svg", resolve("Muse Spark"))
    }
}
