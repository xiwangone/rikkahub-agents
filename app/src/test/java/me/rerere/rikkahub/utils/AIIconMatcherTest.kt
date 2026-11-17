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
        "foo-color.svg", "foo.svg", "bar.svg", "baz.png",
    )

    private fun resolve(name: String) = computeAIIconByName(name, icons)

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
    fun `no match falls back to null`() {
        assertNull(resolve("some-provider-that-does-not-exist"))
        assertNull(resolve(""))
        assertNull(resolve("   "))
        // 裸“火山”仍不归属（旧正则把“火山”判给豆包，属误判）；
        // 完整品牌名“火山方舟”/“火山引擎”已显式登记到 volcengine。
        assertNull(resolve("火山"))
        // 模型名（非品牌名）匹配不到：GLM、llama、o1 不再归到厂商。
        assertNull(resolve("GLM"))
        assertNull(resolve("llama"))
        assertNull(resolve("o1"))
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
