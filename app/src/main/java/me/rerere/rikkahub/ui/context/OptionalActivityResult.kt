package me.rerere.rikkahub.ui.context

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.core.app.ActivityOptionsCompat

/**
 * 独立 Compose 树（悬浮窗等）没有宿主 Activity 时使用的空结果注册表。
 *
 * launch 会被静默丢弃：没有界面可承载结果，调用方回调不会触发。
 * 目的是让 `rememberLauncherForActivityResult` 能正常创建 launcher，不在组合期抛
 * `IllegalStateException: No ActivityResultRegistryOwner was provided`。
 */
private object NoopActivityResultRegistry : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        // 无处投递：静默丢弃
    }
}

/**
 * 空 owner：供独立界面在根节点注入，避免内部组件读不到 owner 而崩溃。
 */
val NoopActivityResultRegistryOwner: ActivityResultRegistryOwner =
    object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = NoopActivityResultRegistry
    }

/**
 * `rememberLauncherForActivityResult` 的统一入口。
 *
 * 不自己造 launcher（`ManagedActivityResultLauncher` 与 `ActivityResultLauncher`
 * 都是带构造的类型，无法自定义实现），而是依赖**宿主树在根节点提供 owner**：
 * - 常规界面：`RikkahubTheme` 注入宿主 Activity
 * - 独立界面（悬浮窗）：`FloatingChatWindow` 注入 [NoopActivityResultRegistryOwner]
 */
@Composable
fun <I, O> rememberSafeLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> =
    rememberLauncherForActivityResult(contract = contract, onResult = onResult)
