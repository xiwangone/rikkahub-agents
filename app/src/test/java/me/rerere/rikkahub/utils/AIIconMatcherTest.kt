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
        "huawei-color.svg", "zhipu-color.svg", "moonshot-color.svg",
        "baichuan-color.svg", "internlm-color.svg", "tiangong-color.svg",
        "zeroone-color.svg", "infinigence-color.svg", "siliconflow-color.svg",
        "metaso-color.svg", "bytedance-color.svg", "minimax-color.svg",
        "xiaomimimo.svg", "open-design.png", "agnesai.svg", "xai.svg", "glmv-color.svg",
        // 模型家族 / 厂商需要用到的
        "anthropic.svg", "qiniu-color.svg", "poolside-color.svg", "internlm-color.svg",
        "minimax-color.svg", "gemma-color.svg", "meta-color.svg", "google-color.svg",
        "stepfun-color.svg", "zhipu-color.svg",
        "foo-color.svg", "foo.svg", "bar.svg", "baz.png",
        // 本批新增：端点别名 amd/minimaxi/tencentmaas 与新图标文件
        "amdradeoncloud-color.svg", "tokenrhythm.png", "suixiang.png", "ackai.png",
    )

    private fun resolve(name: String, baseUrl: String? = null) =
        computeAIIconByName(name, icons, baseUrl)

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
        assertEquals("moonshot-color.svg", resolve("月之暗面"))
        assertEquals("deepseek-color.svg", resolve("深度求索"))
        assertEquals("baichuan-color.svg", resolve("百川智能"))
        assertEquals("internlm-color.svg", resolve("书生浦语"))
        assertEquals("tiangong-color.svg", resolve("昆仑万维"))
        assertEquals("tiangong-color.svg", resolve("天工"))
        assertEquals("zeroone-color.svg", resolve("零一万物"))
        assertEquals("infinigence-color.svg", resolve("无问芯穹"))
        assertEquals("siliconflow-color.svg", resolve("硅基流动"))
        assertEquals("metaso-color.svg", resolve("秘塔"))
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
        assertEquals("moonshot-color.svg", resolve("Moonshot Kimi"))
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
        assertEquals("anthropic.svg", resolve("claude-sonnet-5"))
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
        assertEquals("google-color.svg", resolve("google/gemini-4-argon"))
        assertEquals("deepseek-color.svg", resolve("deepseek/deepseek-v4.1-flash"))
        assertEquals("meta-color.svg", resolve("meta/muse-spark-1.3-contributor"))
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
}
