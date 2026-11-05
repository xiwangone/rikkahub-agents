package me.rerere.rikkahub.ui.context

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat

/**
 * 独立 Compose 树（悬浮窗、后台渲染、侧栏预览等）没有 ActivityResultRegistryOwner。
 *
 * 直接调用 `rememberLauncherForActivityResult` 会在组合期抛
 * `IllegalStateException: No ActivityResultRegistryOwner was provided`，
 * 表现为进入悬浮窗 / 切到后台 / 渲染表格时闪退。
 *
 * 这里提供一个空 registry：launcher 照常创建（调用点无需改动），
 * 但 launch 会被静默丢弃——没有 Activity 可承载结果，调用方回调不会触发。
 * 渲染不崩，属预期降级。
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

private val NoopActivityResultRegistryOwner: ActivityResultRegistryOwner =
    object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = NoopActivityResultRegistry
    }

/**
 * `rememberLauncherForActivityResult` 的安全版本。
 *
 * 有宿主 Activity 时行为完全一致；没有时（独立界面）退回空 registry，
 * 避免组合期崩溃。全仓统一用它，而不是各自判断。
 */
@Composable
fun <I, O> rememberSafeLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val owner = LocalActivityResultRegistryOrNull.current ?: NoopActivityResultRegistryOwner
    var launcher: ManagedActivityResultLauncher<I, O>? = null
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
        launcher = rememberLauncherForActivityResult(contract = contract, onResult = onResult)
    }
    return requireNotNull(launcher) { "launcher not created during composition" }
}
