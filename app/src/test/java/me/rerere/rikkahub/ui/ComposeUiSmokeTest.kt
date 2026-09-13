package me.rerere.rikkahub.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：验证"宿主机 JVM + Robolectric + Compose"这条链路可用
 * （compose rule → 语义树 → 断言/交互），这样 UI 回归可以进 CI 而不需要真机。
 *
 * 版本约束：Robolectric **4.15** 是最后一条能在 **JDK 17** 上跑的线（4.16+ 需 JDK 21），
 * 它支持到 **SDK 35**，而工程 compileSdk = 37 → 因此显式 `@Config(sdk = [35])`。
 * 若将来构建环境升到 JDK 21，可换 Robolectric 4.17 并解除该 pin。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposeUiSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `renders text and exposes it in the semantics tree`() {
        compose.setContent { Text("hello-ui") }
        compose.onNodeWithText("hello-ui").assertIsDisplayed()
    }

    @Test
    fun `click recomposes and updates displayed state`() {
        var observed = -1
        compose.setContent {
            var n by mutableStateOf(0)
            Text(
                text = "count=$n",
                modifier = Modifier.clickable {
                    n += 1
                    observed = n
                },
            )
        }
        compose.onNodeWithText("count=0").assertIsDisplayed()
        compose.onNodeWithText("count=0").performClick()
        compose.onNodeWithText("count=1").assertIsDisplayed()
        assertEquals(1, observed)
    }
}
