package me.rerere.rikkahub.ui.components.ai

import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已选模型失效标记（[ModelListState.isStale]）：已选模型在当前供应商列表里
 * 找不到了（模型被删），与“从没选过”区分显示。注意 `findModelById` 不分
 * 供应商启用状态，禁用供应商的模型仍找得到，不算失效；`DEFAULT_AUTO_MODEL_ID`
 * 是“自动/未选”哨兵，也不算失效。
 */
class ModelListStaleTest {

    private val modelId = Uuid.random()

    private fun state(
        id: Uuid? = modelId,
        providers: List<ProviderSetting> = emptyList(),
    ) = ModelListState(id, providers, ModelType.CHAT)

    private fun providerWithModel(enabled: Boolean = true) =
        ProviderSetting.OpenAI(
            enabled = enabled,
            models = listOf(Model(id = modelId, modelId = "gpt-5")),
        )

    @Test
    fun `never selected is not stale`() {
        assertFalse(state(id = null).isStale)
    }

    @Test
    fun `selected model still present is not stale`() {
        assertFalse(state(providers = listOf(providerWithModel())).isStale)
    }

    @Test
    fun `selected model in disabled provider is not stale`() {
        assertFalse(state(providers = listOf(providerWithModel(enabled = false))).isStale)
    }

    @Test
    fun `auto sentinel is not stale`() {
        // DEFAULT_AUTO_MODEL_ID 是“自动/未选”哨兵（五个非空槽位清掉后就回到它），
        // 不是被删的模型：新装、清掉后都不显示“原模型已删除/不可用”。
        assertFalse(state(id = DEFAULT_AUTO_MODEL_ID).isStale)
    }

    @Test
    fun `selected model deleted is stale`() {
        assertTrue(state(providers = emptyList()).isStale)
        val other =
            ProviderSetting.OpenAI(
                models = listOf(Model(modelId = "gpt-5-mini")),
            )
        assertTrue(state(providers = listOf(other)).isStale)
    }
}
