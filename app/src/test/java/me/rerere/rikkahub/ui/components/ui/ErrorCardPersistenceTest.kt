package me.rerere.rikkahub.ui.components.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.service.ChatError
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 错误卡片不自动消失回归：卡片只应由用户手动关闭或被清除，不应在 5 秒后自行消失。
 *
 * 旧实现在 [ErrorCard] 内用 `LaunchedEffect(error.id) { delay(5000); onDismiss() }`，
 * 用户来不及读完错误内容卡片就没了。本用例渲染卡片后把虚拟时钟推进 10 秒，
 * 断言错误文案仍可见；再点关闭按钮，断言手动关闭仍可用。
 *
 * 单 @Test：RikkaHubApp.onCreate 里 startKoin，Robolectric 每个 @Test 都新建
 * Application，拆成多个方法时后几个在 onCreate 即抛
 * KoinApplicationAlreadyStartedException（同类情况见 ChatInputIconSizeTest）。
 *
 * @Config(application = Application::class) 是承重的：全量单测里别的
 * Robolectric 测试类可能已在共享沙箱里启动过 Koin，用真实 Application
 * 会在 onCreate 的 startKoin 处抛 KoinApplicationAlreadyStartedException
 *（实践中出现过：仅本用例失败、同类用例先跑通过，本用例在 Application
 * 创建阶段即抛错）。 plain Application 完全避开
 * Koin；本用例的 ErrorCard 不碰 Koin。注意不能加 manifest = Config.NONE——
 * 卡片组合时要读 stringResource，需要正常 manifest 提供的资源。
 *（同款做法见 WorkspaceApplyEditsAtomicityTest 的注释。）
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class ErrorCardPersistenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `error card stays visible after 10 seconds and dismiss button still works`() {
        var dismissed = false
        composeRule.setContent {
            ErrorCard(
                error = ChatError(error = RuntimeException("Test error message")),
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Test error message").assertIsDisplayed()
        // 推进虚拟时钟 10 秒：旧实现在 5 秒时调 onDismiss，卡片会消失
        composeRule.mainClock.advanceTimeBy(10_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Test error message").assertIsDisplayed()
        // 手动关闭仍可用
        composeRule.onNodeWithContentDescription("Dismiss error").performClick()
        composeRule.waitForIdle()
        assertTrue("dismiss button must still call onDismiss", dismissed)
    }
}
