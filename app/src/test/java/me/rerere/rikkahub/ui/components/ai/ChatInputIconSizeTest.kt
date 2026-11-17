package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

/**
 * 输入框底部功能栏图标尺寸回归（整排统一 24dp 字形）。
 *
 * 根因：[AutoAIIcon] 经 [me.rerere.rikkahub.ui.components.ui.AIIcon] 渲染，
 * 后者内部固定 `modifier.size(24.dp)` 外框 + 4.dp 内边距。`Modifier.size()` 会把
 * 目标值夹进传入约束范围（enforceIncoming=true）：调用方传 plain `size(36.dp)`
 * 时，内部 24.dp 被夹回 36.dp，外框 36.dp、字形 28.dp——实际值，不是 16.dp；
 * 传 `requiredSize(32.dp)`（enforceIncoming=false，忽略传入约束）才能越过内部
 * 约束、外框 32.dp、字形 24.dp（与 SearchPickerButton 已用的做法一致）。
 * 链语义以 foundation-layout 1.12.0 字节码 + 本测试断言为准。
 *
 * 断言的是 AsyncImage 节点的布局尺寸（= 字形绘制尺寸），与图片是否解码完成无关，
 * 故查询一律用 `useUnmergedTree = true`：ModelSelectorButton 的 44.dp 点击圆圈
 * 带 clickable，会把后代语义合并进自身节点，合并后的节点宽 44.dp，断言它会误报；
 * 只有未合并树里的 Image 节点本身才是字形尺寸。
 *
 * 三个用例写进同一个 @Test：RikkaHubApp.onCreate 里 startKoin，Robolectric 每个
 * @Test 都新建 Application，拆成三个方法时后两个在 onCreate 即抛
 * KoinApplicationAlreadyStartedException（曾导致 2/3 用例失败）。
 * 单方法只建一次 Application、Koin 只启动一次。不要在 @After 里 stopKoin()：
 * Robolectric 拆 Application 时调 RikkaHubApp.onTerminate，后者要用 Koin，
 * 先停了就抛 "KoinApplication has not been started"（已确认；同类情况见
 * WorkspaceApplyEditsAtomicityTest 的注释）。跨测试类不存在残留问题——
 * Robolectric 每个测试类跑在独立沙箱（独立类加载器）里，静态的 Koin 上下文
 * 互不干扰。ModelSelectorButton 本身不碰 Koin（koinInject 在 ModelList 列表
 * 里，不在按钮里），故单方法 + 真实 Application 即可。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ChatInputIconSizeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `input bar icon glyphs are 24dp`() {
        // 同一 setContent 内用 state 换三段内容：createComposeRule 的 setContent
        // 只能调一次，第二次调即抛 IllegalStateException（已确认）。
        var content by mutableStateOf<@Composable () -> Unit>({})
        composeRule.setContent { content() }

        // 改后行为：requiredSize(32) 越过 AIIcon 内部 size(24) 约束，字形 24.dp。
        content = {
            AutoAIIcon(
                name = "deepseek",
                modifier = Modifier.requiredSize(32.dp),
                color = Color.Transparent,
            )
        }
        composeRule.onNodeWithContentDescription("deepseek", useUnmergedTree = true)
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)

        // 旧行为留档：plain size(36) 时字形 28.dp，证明测试能区分两种链。
        content = {
            AutoAIIcon(
                name = "deepseek",
                modifier = Modifier.size(36.dp),
                color = Color.Transparent,
            )
        }
        composeRule.onNodeWithContentDescription("deepseek", useUnmergedTree = true)
            .assertWidthIsEqualTo(28.dp)
            .assertHeightIsEqualTo(28.dp)

        // 改动的真实调用点：ModelSelectorButton(onlyIcon = true) 配 deepseek 模型，字形 24.dp。
        val modelUuid = Uuid.random()
        val state = ModelListState(
            modelId = modelUuid,
            providers = listOf(
                ProviderSetting.OpenAI(
                    models = listOf(
                        Model(id = modelUuid, modelId = "deepseek", displayName = "DeepSeek")
                    ),
                    apiKey = "",
                )
            ),
            type = ModelType.CHAT,
        )
        content = {
            ModelSelectorButton(state = state, onlyIcon = true)
        }
        composeRule.onNodeWithContentDescription("deepseek", useUnmergedTree = true)
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)
    }
}
