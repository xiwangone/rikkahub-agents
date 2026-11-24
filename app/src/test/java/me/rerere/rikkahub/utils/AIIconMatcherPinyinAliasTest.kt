package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class AIIconMatcherPinyinAliasTest {

    private val fakeIcons = setOf(
        "baai.svg",
        "flux.svg",
        "bfl.svg",
        "qwen-color.svg",
        "wenxin-color.svg",
        "zhipu-color.svg",
        "volcengine-color.svg",
        "spark-color.svg",
        "hunyuan-color.svg",
        "moonshot.svg",
        "deepseek-color.svg",
        "siliconflow.svg",
        "stepfun-color.svg",
        "perplexity-color.svg",
        "openai.svg",
        "google-color.svg",
        "qiniu-color.svg",
        "alibaba-color.svg",
        "baidu-color.svg",
        "antgroup-color.svg",
    )

    @Test
    fun `matches pinyin and chinese aliases accurately`() {
        assertEquals("qwen-color.svg", computeAIIconByName("tongyi", fakeIcons))
        assertEquals("qwen-color.svg", computeAIIconByName("qianwen", fakeIcons))
        assertEquals("wenxin-color.svg", computeAIIconByName("wenxin", fakeIcons))
        assertEquals("zhipu-color.svg", computeAIIconByName("qingyan", fakeIcons))
        assertEquals("volcengine-color.svg", computeAIIconByName("huoshanfangzhou", fakeIcons))
        assertEquals("spark-color.svg", computeAIIconByName("xunfeixinghuo", fakeIcons))
        assertEquals("hunyuan-color.svg", computeAIIconByName("tengxunhunyuan", fakeIcons))
        assertEquals("moonshot.svg", computeAIIconByName("yuezhianmian", fakeIcons))
        assertEquals("deepseek-color.svg", computeAIIconByName("shenduqiusuo", fakeIcons))
        assertEquals("siliconflow.svg", computeAIIconByName("guijiliudong", fakeIcons))
        assertEquals("stepfun-color.svg", computeAIIconByName("jieyuexingchen", fakeIcons))
        assertEquals("baai.svg", computeAIIconByName("智源研究院", fakeIcons))
        assertEquals("bfl.svg", computeAIIconByName("黑森林", fakeIcons))
    }

    @Test
    fun `matches bge flux sonar and media model families`() {
        assertEquals("baai.svg", computeModelIconByName("bge-m3", fakeIcons))
        assertEquals("baai.svg", computeModelIconByName("bge-reranker-base", fakeIcons))
        assertEquals("flux.svg", computeModelIconByName("flux-2-klein-9b", fakeIcons))
        assertEquals("perplexity-color.svg", computeModelIconByName("sonar-pro", fakeIcons))
        assertEquals("openai.svg", computeModelIconByName("whisper-large-v3", fakeIcons))
        assertEquals("google-color.svg", computeModelIconByName("imagen-3.0-generate", fakeIcons))
        assertEquals("zhipu-color.svg", computeModelIconByName("cogview-4", fakeIcons))
        assertEquals("openai.svg", computeModelIconByName("text-embedding-3-small", fakeIcons))
        assertEquals("qwen-color.svg", computeModelIconByName("Tongyi-MAI/Z-Image-Turbo", fakeIcons))
        assertEquals("qwen-color.svg", computeModelIconByName("z-image-turbo", fakeIcons))
        assertEquals("qwen-color.svg", computeModelIconByName("gui-plus-2026-02-26", fakeIcons))
        assertEquals("alibaba-color.svg", computeModelIconByName("FunAudioLLM/CosyVoice2-0.5B", fakeIcons))
        assertEquals("alibaba-color.svg", computeModelIconByName("FunAudioLLM/SenseVoiceSmall", fakeIcons))
        assertEquals("alibaba-color.svg", computeModelIconByName("fun-asr-flash-2026-06-15", fakeIcons))
        assertEquals("baidu-color.svg", computeModelIconByName("PaddlePaddle/PaddleOCR-VL-1.5", fakeIcons))
        assertEquals("antgroup-color.svg", computeModelIconByName("MedAIBase/AntAngelMed", fakeIcons))
    }

    @Test
    fun `matches qiniu provider aliases and api hostnames`() {
        assertEquals("qiniu-color.svg", computeAIIconByName("qiniuyun", fakeIcons))
        assertEquals("qiniu-color.svg", computeAIIconByName("qiniucloud", fakeIcons))
        assertEquals("qiniu-color.svg", computeAIIconByName("七牛云", fakeIcons))
        assertEquals("qiniu-color.svg", computeAIIconByName("Custom", fakeIcons, "https://api.qnaigc.com/v1"))
        assertEquals("qiniu-color.svg", computeAIIconByName("Custom", fakeIcons, "https://openai.qiniu.com/v1"))
    }
}
