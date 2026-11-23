package me.rerere.rikkahub.data.ai.catalog

import android.app.Application
import android.content.Context
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelCatalogBridge
import me.rerere.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 能力解析（生效值 + 来源）的回归测试。
 *
 * 覆盖：未知模型回退默认；基线表 7 条商汤/DeepSeek/Kimi/GLM 条目按官方
 * `/models` 元数据解析（图像输出模型 `sensenova-u1.5-lite` 在内）；
 * OpenRouter 错配分析的 9 条纠正（qwen3 非 thinking 变体去推理、
 * gpt-3.5-turbo 去图像输入与推理、gpt-5-image 系去工具改图像输出，
 * thinking 变体不受影响）；DeepSeek v4 系列上下文 1048576；
 * 视频模型输出 VIDEO（视频批新增 Modality.VIDEO 后，标签与编辑多选须覆盖）；
 * 本机覆盖遮挡基线；编辑器保存路径（upsert → 重载 → 解析）生效后可再删除回基线。
 *
 * 基线表来自 `assets/model-capabilities.json`：若当前 runner 未合并 assets，
 * 基线相关用例跳过（Assume），其余用例不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelCapabilityResolverTest {

    private lateinit var context: Context
    private lateinit var overrides: ModelCapabilityOverrides

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.resolve("model-capabilities.json").delete()
        // 把并集层钉在“内置表”上：桥置空、目录置空，避免其他单测的全局注入
        // 漏进来导致回落值不确定；空提供者与未注入行为一致（全部返回 null）。
        ModelCatalogBridge.install(
            object : ModelCatalogBridge.Provider {
                override fun inputModalities(modelId: String) = null
                override fun outputModalities(modelId: String) = null
                override fun abilities(modelId: String) = null
            },
        )
        ModelRegistry.installCatalog(null)
        overrides = ModelCapabilityOverrides(context)
        overrides.load()
    }

    @Test
    fun unknownModelFallsBackToDefault() {
        val resolved = ModelCapabilityResolver.resolve("zzz-unknown-model-qqq", overrides)
        assertEquals(setOf(Modality.TEXT), resolved.input)
        assertEquals(CapabilitySource.DEFAULT, resolved.inputSource)
        assertEquals(setOf(Modality.TEXT), resolved.output)
        assertEquals(CapabilitySource.DEFAULT, resolved.outputSource)
        assertEquals(emptySet<ModelAbility>(), resolved.abilities)
        assertEquals(CapabilitySource.DEFAULT, resolved.abilitiesSource)
        assertNull(resolved.contextLength)
        assertEquals(CapabilitySource.DEFAULT, resolved.contextLengthSource)
    }

    @Test
    fun baselineEntriesResolveWithBaselineSource() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        val flash = ModelCapabilityResolver.resolve("sensenova-6.8-flash", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), flash.input)
        assertEquals(CapabilitySource.BASELINE, flash.inputSource)
        assertEquals(setOf(Modality.TEXT), flash.output)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), flash.abilities)
        assertEquals(CapabilitySource.BASELINE, flash.abilitiesSource)
        assertEquals(262144, flash.contextLength)
        assertEquals(CapabilitySource.BASELINE, flash.contextLengthSource)

        val lite = ModelCapabilityResolver.resolve("sensenova-6.8-flash-lite", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), lite.input)
        assertEquals(CapabilitySource.BASELINE, lite.inputSource)
        assertEquals(262144, lite.contextLength)

        val deepseek = ModelCapabilityResolver.resolve("deepseek-flash", overrides)
        assertEquals(setOf(Modality.TEXT), deepseek.input)
        assertEquals(CapabilitySource.BASELINE, deepseek.inputSource)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), deepseek.abilities)
        assertEquals(1048576, deepseek.contextLength)

        val kimi = ModelCapabilityResolver.resolve("kimi-k3", overrides)
        assertEquals(setOf(Modality.TEXT), kimi.input)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), kimi.abilities)
        assertEquals(1048576, kimi.contextLength)

        val glm = ModelCapabilityResolver.resolve("glm-5.3", overrides)
        assertEquals(setOf(Modality.TEXT), glm.input)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), glm.abilities)
        assertEquals(1048576, glm.contextLength)
    }

    @Test
    fun imageOutputModelResolvesOutputImage() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        val resolved = ModelCapabilityResolver.resolve("sensenova-u1.5-lite", overrides)
        assertEquals(setOf(Modality.TEXT), resolved.input)
        assertEquals(setOf(Modality.IMAGE), resolved.output)
        assertEquals(CapabilitySource.BASELINE, resolved.outputSource)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), resolved.abilities)
        assertEquals(262144, resolved.contextLength)
    }

    @Test
    fun localOverrideShadowsBaseline() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(
                    match = "sensenova-6.8-flash",
                    input = setOf(Modality.TEXT),
                ),
            ),
        )
        val resolved = ModelCapabilityResolver.resolve("sensenova-6.8-flash", overrides)
        assertEquals(setOf(Modality.TEXT), resolved.input)
        assertEquals(CapabilitySource.LOCAL, resolved.inputSource)
        // 未覆盖的字段回落到并集（内置表），不去查基线表同字段：内置表不认识
        // sensenova，故能力为空、上下文未知，而非基线条目的 tool+reasoning / 262144。
        assertEquals(emptySet<ModelAbility>(), resolved.abilities)
        assertEquals(CapabilitySource.DEFAULT, resolved.abilitiesSource)
        assertNull(resolved.contextLength)
        assertEquals(CapabilitySource.DEFAULT, resolved.contextLengthSource)
    }

    @Test
    fun editorSavePathTakesEffectAfterReloadAndCanBeRemoved() {
        // 编辑器保存路径：改其中一条、保存、重载后生效；删除后回落到基线。
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(
                    match = "kimi-k3",
                    contextLength = 500_000,
                ),
            ),
        )
        overrides.load()
        val edited = ModelCapabilityResolver.resolve("kimi-k3", overrides)
        assertEquals(500_000, edited.contextLength)
        assertEquals(CapabilitySource.LOCAL, edited.contextLengthSource)
        // 未覆盖的输入回落并集：内置表 KIMI_K3 带视觉输入，故为 T+图（MERGED），
        // 而非基线条目的纯文本——这正是基线要纠正的误标。
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), edited.input)
        assertEquals(CapabilitySource.MERGED, edited.inputSource)

        assertTrue(overrides.removeLocal("kimi-k3"))
        overrides.load()
        val restored = ModelCapabilityResolver.resolve("kimi-k3", overrides)
        assertEquals(1048576, restored.contextLength)
        assertEquals(CapabilitySource.BASELINE, restored.contextLengthSource)
    }

    @Test
    fun openRouterQwen3NonThinkingVariantsStripReasoning() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        // 内置 QWEN_3 规则把全部 qwen3-* 标成推理；基线表把 10 个非 thinking
        // 变体纠回仅工具，输入/输出与上下文仍走内置（条目里未写）。
        val ids = listOf(
            "qwen/qwen3-235b-a22b-2507",
            "qwen/qwen3-30b-a3b-instruct-2507",
            "qwen/qwen3-coder",
            "qwen/qwen3-coder-30b-a3b-instruct",
            "qwen/qwen3-coder-flash",
            "qwen/qwen3-coder-next",
            "qwen/qwen3-next-80b-a3b-instruct",
            "qwen/qwen3-vl-235b-a22b-instruct",
            "qwen/qwen3-vl-30b-a3b-instruct",
            "qwen/qwen3-vl-8b-instruct",
        )
        for (id in ids) {
            val resolved = ModelCapabilityResolver.resolve(id, overrides)
            assertEquals(id, setOf(ModelAbility.TOOL), resolved.abilities)
            assertEquals(id, CapabilitySource.BASELINE, resolved.abilitiesSource)
        }
        // VL 系视觉输入不受影响（内置 QWEN_3_VL 的视觉仍在）。
        val vl = ModelCapabilityResolver.resolve("qwen/qwen3-vl-8b-instruct", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), vl.input)
        assertEquals(CapabilitySource.MERGED, vl.inputSource)
    }

    @Test
    fun openRouterQwen3ThinkingVariantsKeepReasoning() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        // thinking 变体的 id 不含基线条目的 match 串，推理保留（内置）。
        val thinking = ModelCapabilityResolver.resolve("qwen/qwen3-235b-a22b-thinking-2507", overrides)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), thinking.abilities)
        assertEquals(CapabilitySource.MERGED, thinking.abilitiesSource)
        val base = ModelCapabilityResolver.resolve("qwen/qwen3-30b-a3b", overrides)
        assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), base.abilities)
        assertEquals(CapabilitySource.MERGED, base.abilitiesSource)
    }

    @Test
    fun openRouterGpt35TurboCorrected() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        // 内置 GPT_5 规则按 token 命中 gpt-3.5-turbo，误标图像输入与推理。
        val old = ModelCapabilityResolver.resolve("openai/gpt-3.5-turbo-0613", overrides)
        assertEquals(setOf(Modality.TEXT), old.input)
        assertEquals(CapabilitySource.BASELINE, old.inputSource)
        assertEquals(setOf(ModelAbility.TOOL), old.abilities)
        assertEquals(CapabilitySource.BASELINE, old.abilitiesSource)
        assertEquals(4095, old.contextLength)
        assertEquals(CapabilitySource.BASELINE, old.contextLengthSource)
        val large = ModelCapabilityResolver.resolve("openai/gpt-3.5-turbo-16k", overrides)
        assertEquals(setOf(Modality.TEXT), large.input)
        assertEquals(setOf(ModelAbility.TOOL), large.abilities)
        assertEquals(16385, large.contextLength)
    }

    @Test
    fun openRouterGpt5ImageCorrected() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        // gpt-5-image 系是纯图像生成：无工具、有推理、输出含图像。
        val image = ModelCapabilityResolver.resolve("openai/gpt-5-image", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), image.input)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), image.output)
        assertEquals(CapabilitySource.BASELINE, image.outputSource)
        assertEquals(setOf(ModelAbility.REASONING), image.abilities)
        assertEquals(CapabilitySource.BASELINE, image.abilitiesSource)
        assertEquals(400000, image.contextLength)
        val mini = ModelCapabilityResolver.resolve("openai/gpt-5-image-mini", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), mini.output)
        assertEquals(setOf(ModelAbility.REASONING), mini.abilities)
        assertEquals(400000, mini.contextLength)
        val v54 = ModelCapabilityResolver.resolve("openai/gpt-5.4-image-2", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), v54.output)
        assertEquals(setOf(ModelAbility.REASONING), v54.abilities)
        assertEquals(272000, v54.contextLength)
    }

    @Test
    fun deepseekV4ContextLengthIs1048576() {
        // 内置 v4 系列上下文由 1000000 改为 1048576（OpenRouter 官方值）。
        val flash41 = ModelCapabilityResolver.resolve("deepseek/deepseek-v4.1-flash", overrides)
        assertEquals(1048576, flash41.contextLength)
        assertEquals(CapabilitySource.MERGED, flash41.contextLengthSource)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), flash41.input)
        val pro = ModelCapabilityResolver.resolve("deepseek/deepseek-v4-pro", overrides)
        assertEquals(1048576, pro.contextLength)
        assertEquals(CapabilitySource.MERGED, pro.contextLengthSource)
        assertEquals(setOf(Modality.TEXT), pro.input)
    }

    @Test
    fun videoOutputModelResolvesOutputVideo() {
        // 视频批新增 Modality.VIDEO 后，生效视图应能展示视频输出（编辑器多选
        // 框走 Modality.entries，视频项自动出现；标签函数须有 VIDEO 分支）。
        val veo = ModelCapabilityResolver.resolve("veo-3", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.VIDEO), veo.output)
        assertEquals(CapabilitySource.MERGED, veo.outputSource)
        val sora = ModelCapabilityResolver.resolve("sora-2", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.VIDEO), sora.output)
    }

    @Test
    fun localEntriesAreVisibleForEditing() {
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(
                    match = "deepseek-flash",
                    abilities = emptySet(),
                ),
            ),
        )
        assertEquals(1, overrides.localEntryCount())
        assertEquals("deepseek-flash", overrides.localEntries().single().match)
        val resolved = ModelCapabilityResolver.resolve("deepseek-flash", overrides)
        // 空能力集也是有效覆盖（纠正误标），不是“不覆盖”
        assertEquals(emptySet<ModelAbility>(), resolved.abilities)
        assertEquals(CapabilitySource.LOCAL, resolved.abilitiesSource)
    }

    @Test
    fun longestMatchWinsWithinLocalTable() {
        // 同表两条都命中时取 match 最长者：短前缀 "deepseek" 不得抢在
        // 更具体的 "deepseek-flash" 之前生效（旧 firstOrNull 行为）。
        assertTrue(overrides.upsertLocal(ModelCapabilityOverrides.Entry(match = "deepseek", contextLength = 111)))
        assertTrue(overrides.upsertLocal(ModelCapabilityOverrides.Entry(match = "deepseek-flash", contextLength = 222)))
        val resolved = ModelCapabilityResolver.resolve("deepseek-flash", overrides)
        assertEquals(222, resolved.contextLength)
        assertEquals(CapabilitySource.LOCAL, resolved.contextLengthSource)
        // 未被长条目覆盖的模型仍由短条目兜底
        val fallback = ModelCapabilityResolver.resolve("deepseek-other-variant", overrides)
        assertEquals(111, fallback.contextLength)
    }

    @Test
    fun longerBaselineEntryBeatsShorterLocalEntry() {
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        // 跨表最长匹配：本机表短条目 "deepseek" 不再因“本机优先”抢在基线表
        // 更具体的 "deepseek-flash" 之前（旧行为）。
        assertTrue(overrides.upsertLocal(ModelCapabilityOverrides.Entry(match = "deepseek", contextLength = 111)))
        val resolved = ModelCapabilityResolver.resolve("deepseek-flash", overrides)
        assertEquals(1048576, resolved.contextLength)
        assertEquals(CapabilitySource.BASELINE, resolved.contextLengthSource)
    }

    @Test
    fun localShortMatchDoesNotShadowBaselineSpecificEntry() {
        // 本机表加一条短串 `mimo`，不应把基线表里更具体的 `mimo-v2.6` 挡在后面。
        // 基线条目对 mimo-v2.6 的输入是文本+图像；短串若抢占，会得到纯文本（LOCAL）。
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(match = "mimo", input = setOf(Modality.TEXT)),
            ),
        )
        overrides.load()
        val specific = ModelCapabilityResolver.resolve("mimo-v2.6", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), specific.input)
        assertEquals(CapabilitySource.BASELINE, specific.inputSource)
        // 短串本身仍然生效：没有更长条目命中的型号走本机表
        val shortOnly = ModelCapabilityResolver.resolve("mimo-unknown", overrides)
        assertEquals(setOf(Modality.TEXT), shortOnly.input)
        assertEquals(CapabilitySource.LOCAL, shortOnly.inputSource)
        overrides.removeLocal("mimo")
    }

    @Test
    fun importRejectsTableWhoseEntriesAreAllInvalid() {
        // 有槽位但一条都解析不出来：拒绝，不能把本机表写成空表。
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(match = "keep-me", contextLength = 4096),
            ),
        )
        overrides.load()
        assertEquals(1, overrides.localEntryCount())
        val rejected = overrides.importLocal("""{"version":1,"models":[{},{"input":["text"]}]}""")
        assertTrue(!rejected)
        assertEquals(1, overrides.localEntryCount())
        assertEquals("keep-me", overrides.localEntries().single().match)
    }

    @Test
    fun importAcceptsExplicitEmptyTable() {
        // 显式空表是明确的“清空”意图，应当接受（等价于 clearLocal）。
        assertTrue(
            overrides.upsertLocal(ModelCapabilityOverrides.Entry(match = "temp", contextLength = 1)),
        )
        overrides.load()
        assertTrue(overrides.importLocal("""{"version":1,"models":[]}"""))
        assertEquals(0, overrides.localEntryCount())
    }

    @Test
    fun writesLeaveNoStaleTempFile() {
        // 原子写：落盘后不应残留 .tmp；目标文件内容与解析结果一致。
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(match = "atomic-check", contextLength = 2048),
            ),
        )
        val tmp = context.filesDir.resolve("model-capabilities.json.tmp")
        assertTrue("temp file should be gone after rename", !tmp.exists())
        val target = context.filesDir.resolve("model-capabilities.json")
        assertTrue("target should exist", target.exists())
        assertEquals(1, overrides.localEntryCount())
        assertEquals(2048, overrides.localEntries().single().contextLength)
    }

    @Test
    fun editorViewAgreesWithEffectiveLookup() {
        // 编辑器展示（resolve）与模型实际取值（overrides.*）必须同源。
        // 两处若一个「本机表优先」、一个「合并后最长」，下面两组对照就会分叉。
        assumeTrue("baseline table not loaded under this runner", overrides.baselineEntryCount() == 19)
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(match = "mimo", input = setOf(Modality.TEXT)),
            ),
        )
        overrides.load()
        // 基线条目更长：两边都取基线
        val specific = ModelCapabilityResolver.resolve("mimo-v2.6", overrides)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), specific.input)
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), overrides.inputModalities("mimo-v2.6"))

        // 本机条目更长：两边都取本机
        assertTrue(
            overrides.upsertLocal(
                ModelCapabilityOverrides.Entry(match = "kimi-k3", contextLength = 500_000),
            ),
        )
        overrides.load()
        val kimi = ModelCapabilityResolver.resolve("kimi-k3", overrides)
        assertEquals(CapabilitySource.LOCAL, kimi.contextLengthSource)
        assertEquals(500_000, kimi.contextLength ?: -1)
        assertEquals(500_000, overrides.contextLength("kimi-k3") ?: -1)

        overrides.removeLocal("mimo")
        overrides.removeLocal("kimi-k3")
    }
}
