package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }


    @Test
    fun testAggregatorEndpointModels() {
        // 聚合端点常用模型：确认能力能被正确推断（名称即其 API 模型 id 形态）
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4.1-flash"),
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4.1-flash"),
        )
        // LongCat 2.0：文本 + 工具 + 推理
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("longcat-2.0"),
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("longcat-2.0"),
        )
        // 本轮新增的视觉模型
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gemini-3.8-flash"),
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("ling-3.0-flash-vl"),
        )
        // 腾讯混元 HY3：文本 + 工具 + 推理
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("hy3"),
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-sonnet-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("claude-opus-5")
        )
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }

    @Test
    fun testMuseSparkAndGlimmer() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark-1.2"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-glimmer"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-glimmer-30b"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("muse-spark"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("muse-glimmer-30b"))
    }

    @Test
    fun testXiaomiMimo() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val textOnly = listOf(Modality.TEXT)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        // v2.6 全系支持图像输入
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6-flash"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6-pro"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6-pro-ultraspeed"))
        // v2.5 支持图像，但 v2.5-pro 与语音类不带
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5"))
        assertEquals(textOnly, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5-pro"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.6-flash"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.5"))
    }

    /** 把外部目录的来源换成空实现，供用例在 finally 里还原全局单例。 */
    private fun installEmptyCatalog() {
        ModelCatalogBridge.install(
            object : ModelCatalogBridge.Provider {
                override fun inputModalities(modelId: String) = null
                override fun outputModalities(modelId: String) = null
                override fun abilities(modelId: String) = null
            },
        )
    }

    @Test
    fun testCatalogBridgeMergesCapabilitiesWithBuiltinRegistry() {
        // 内置表命中时，目录若声明了内置没有的能力，应取并集补上（防“宽泛规则吃掉新版本”）。
        ModelCatalogBridge.install(
            object : ModelCatalogBridge.Provider {
                override fun inputModalities(modelId: String) =
                    if (modelId == "mimo-v2.5-pro") setOf(Modality.TEXT, Modality.IMAGE) else null

                override fun outputModalities(modelId: String) = null

                override fun abilities(modelId: String) =
                    if (modelId == "mimo-v2.5-pro") setOf(ModelAbility.TOOL, ModelAbility.REASONING) else null
            },
        )
        try {
            // 内置表说 v2.5-pro 是 text-only，目录说有 image → 并集后应有 image
            assertEquals(
                listOf(Modality.TEXT, Modality.IMAGE),
                ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5-pro"),
            )
            // 未命中的模型：目录置空时应退回默认纯文本
            assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("some-unknown-model"))
        } finally {
            installEmptyCatalog()
        }
    }

    @Test
    fun testCatalogBridgeOverrideTakesPrecedenceOverBuiltin() {
        // 外置表（覆盖层）命中时直接采用，不再与内置表取并集：
        // 内置表说 mimo-v2.5 支持图像，外置表说只支持文本 → 应得 text-only（能“纠错”）
        ModelCatalogBridge.install(
            object : ModelCatalogBridge.Provider {
                override fun inputModalities(modelId: String) = null

                override fun outputModalities(modelId: String) = null

                override fun abilities(modelId: String) = null

                override fun overrideInputModalities(modelId: String) =
                    if (modelId == "mimo-v2.5") setOf(Modality.TEXT) else null
            },
        )
        try {
            assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5"))
            // 未命中的模型仍走内置表（不受覆盖层影响）
            assertEquals(
                listOf(Modality.TEXT, Modality.IMAGE),
                ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6-flash"),
            )
        } finally {
            installEmptyCatalog()
        }
    }

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash-vision-exp")
        )
    }

    @Test
    fun testStep5() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-5"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-5-preview"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("step-5"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-3"))
    }
}
