package me.rerere.rikkahub.ui.pages.setting

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量移除目标集的判定（`batchRemoveTargets`）。
 *
 * 覆盖两条分支：搜索中删「当前筛选结果」，未搜索时删「全部非对话模型」。
 * 这层判定原本内联在 Composable 里（无法单测），抽成纯函数后由本用例钉住，
 * 不再依赖界面上点确认框来验证。
 */
class BatchRemoveTargetsTest {

    private fun provider(models: List<Model>) = ProviderSetting.OpenAI(
        name = "test",
        models = models,
    )

    private fun model(id: String, type: ModelType) = Model(
        modelId = id,
        displayName = id,
        type = type,
    )

    @Test
    fun withSearchQuery_returnsDisplayedModels() {
        val chat = model("gpt-4o", ModelType.CHAT)
        val image = model("dall-e", ModelType.IMAGE)
        val setting = provider(listOf(chat, image))
        // 搜索结果只剩图片模型：批量移除应只针对它，不动未出现在结果里的条目
        val result = batchRemoveTargets(setting, displayedModels = listOf(image), searchQuery = "dall")
        assertEquals(listOf("dall-e"), result.map { it.modelId })
    }

    @Test
    fun withoutSearchQuery_returnsNonChatModelsOnly() {
        val chat = model("gpt-4o", ModelType.CHAT)
        val image = model("dall-e", ModelType.IMAGE)
        val embedding = model("text-embedding-3", ModelType.EMBEDDING)
        val setting = provider(listOf(chat, image, embedding))
        val result = batchRemoveTargets(setting, displayedModels = emptyList(), searchQuery = "")
        assertEquals(listOf("dall-e", "text-embedding-3"), result.map { it.modelId })
        assertTrue("对话模型不应进入批量移除目标", result.none { it.type == ModelType.CHAT })
    }

    @Test
    fun withoutSearchQuery_andAllChat_returnsEmpty() {
        val setting = provider(
            listOf(model("gpt-4o", ModelType.CHAT), model("claude", ModelType.CHAT)),
        )
        assertTrue(batchRemoveTargets(setting, displayedModels = emptyList(), searchQuery = "  ").isEmpty())
    }

    @Test
    fun withoutSearchQuery_andNoModels_returnsEmpty() {
        val setting = provider(emptyList())
        assertTrue(batchRemoveTargets(setting, displayedModels = emptyList(), searchQuery = "").isEmpty())
    }
}
