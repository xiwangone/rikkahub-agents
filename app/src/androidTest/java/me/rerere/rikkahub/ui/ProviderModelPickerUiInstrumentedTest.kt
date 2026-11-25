package me.rerere.rikkahub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.JsonTree
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.computeAIIconByName
import me.rerere.rikkahub.utils.computeModelIconByName
import me.rerere.rikkahub.utils.listBundledIconNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderModelPickerUiInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun bundledIconsCacheAndMatchRealAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = listBundledIconNames(context)
        val second = listBundledIconNames(context)
        assertTrue("Expected >1000 bundled icons, got ${first.size}", first.size > 1000)
        assertSame("listBundledIconNames should return cached process-level Set", first, second)
        assertEquals("qiniu-color.svg", computeAIIconByName("qiniuyun", first))
        assertEquals("opencompass.png", computeModelIconByName("opencompass/CompassJudger-1-32B-Instruct", first))
        assertEquals("openmoss.png", computeModelIconByName("OpenMOSS-Team/MOSS-Image", first))
        assertEquals("heygen.png", computeModelIconByName("heygen/heygen-avatar-6", first))
        assertEquals("sourceful.png", computeModelIconByName("sourceful/riverflow-v2.6-pro", first))
        assertEquals("unisound.png", computeModelIconByName("unisound/unisound-u2", first))
    }

    @Test
    fun jsonTreeRendersInLightAndDarkThemes() {
        val sample = Json.parseToJsonElement("""{"provider":"qiniuyun","enabled":true,"count":42}""")
        // setContent 只能调一次：亮/暗切换走 state 触发重组，
        // 同一测试内第二次 setContent 会抛 has already set content。
        var dark by mutableStateOf(false)
        composeRule.setContent {
            // JsonTree 经 rememberRenderProfile 读 LocalSettings（无默认值），
            // 测试内没有应用根部的提供者，须在此提供一份默认 Settings
            //（与 DataTable/Markdown 的组件内兜底写法一致）。
            CompositionLocalProvider(LocalSettings provides Settings()) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    JsonTree(json = sample, initialExpandLevel = 2)
                }
            }
        }
        composeRule.onNodeWithText("\"qiniuyun\"").assertIsDisplayed()

        dark = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("42").assertIsDisplayed()
    }
}
