package me.rerere.rikkahub.ui.context

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.app.ActivityOptionsCompat

/**
 * 独立 Compose 树（悬浮窗、后台渲染、侧栏预览等）没有 ActivityResultRegistryOwner。
 *
 * 直接调用 `rememberLauncherForActivityResult` 会在组合期抛
 * `IllegalStateException: No ActivityResultRegistryOwner was provided`，
 * 表现为进入悬浮窗 / 切到后台 / 渲染表格时闪退。
 */
private class NoopActivityResultLauncher<I, O>(
    override val contract: ActivityResultContract<I, O>,
) : ManagedActivityResultLauncher<I, O> {
    override fun launch(input: I, options: ActivityOptionsCompat?) {
        // 没有宿主界面可承载结果：静默丢弃，调用方回调不会触发
    }

    override fun unregister() = Unit
}

/**
 * `rememberLauncherForActivityResult` 的安全版本。
 *
 * 有宿主 Activity 时行为完全一致；没有时（独立界面）返回一个空 launcher，
 * 避免组合期崩溃。
 *
 * 注意：**不能**用 `CompositionLocalProvider { launcher = ... }` 接返回值 ——
 * 重组时 content lambda 会被跳过、赋值不执行，局部变量拿不到值会抛
 * `launcher not created during composition`（重组跳过时界面上会直接崩）。
 */
@Composable
fun <I, O> rememberSafeLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val hasHost = LocalActivityResultRegistryOrNull.current != null
    return if (hasHost) {
        rememberLauncherForActivityResult(contract = contract, onResult = onResult)
    } else {
        remember(contract) { NoopActivityResultLauncher(contract) }
    }
}
