package me.rerere.rikkahub.ui.pages.setting.components

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 后端提供商密码字段是 SecretRefField（凭证库引用入口），不是普通密码框。
 *
 * 旧实现是带可见切换的 OutlinedTextField（明文留在设置里）；改后走 SecretRefField，
 * 粘贴明文可一键入库、选已有引用回填 `$$名`。
 *
 * 判定依据：SecretRefField 的尾部钥匙按钮（contentDescription = R.string.vault_ref_pick，
 * 「从凭证库选择」）是它的标志性入口，普通 OutlinedTextField 没有。默认 Backend 的
 * 密码与令牌两处都是 SecretRefField，共 2 个；旧实现只有令牌一处。
 *
 * 注意：不要改用 vault 引用提示（R.string.vault_ref_hint）计数——两处字段里只有
 * 一处会把占位提示暴露进语义树的 Text 属性（另一处不暴露），计数恒为 1，
 * 与是不是 SecretRefField 无关；钥匙按钮计数才是可靠探针。
 *
 * Koin 安排（承重，照 ErrorCardPersistenceTest 的注释）：
 * 全量单测里别的 Robolectric 测试类可能已在共享沙箱里启动过 Koin，用真实
 * Application 会在 onCreate 的 startKoin 处抛 KoinApplicationAlreadyStartedException，
 * 所以这里用 plain Application；但 SecretRefField 在组合时 koinInject 四个
 * 仓库/偏好对象，没 Koin 又组合不起来——于是 @Before 里先停掉沙箱里残留的
 * Koin，再用真实 app 模块起一个，@After 再停掉，避免污染后跑的类。
 *
 * 单 @Test 方法：Robolectric 每个 @Test 新建 Application、RikkaHubApp.onCreate
 * 里 startKoin，拆成多方法时后者会在 onCreate 即抛 KoinApplicationAlreadyStartedException
 *（同类情况见 ChatInputIconSizeTest 的注释）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class BackendProviderConfigureTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        runCatching { stopKoin() }
        startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun `backend password field is a SecretRefField`() {
        val pickDesc = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.vault_ref_pick)
        composeRule.setContent {
            BackendProviderConfigure(
                provider = ProviderSetting.Backend(),
                onEdit = {},
            )
        }
        composeRule.onAllNodesWithContentDescription(pickDesc, useUnmergedTree = true)
            .assertCountEquals(2)
    }
}
