package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.perf.RenderProfile
import me.rerere.rikkahub.data.perf.RenderTierDiagnostics
import me.rerere.rikkahub.data.perf.detectDeviceProfile
import me.rerere.rikkahub.data.perf.resolveRenderProfile

/**
 * 当前生效的渲染档位。
 *
 * 只依赖「用户偏好 + 设备能力」两个稳定输入，组合期成本可忽略；档位变化时留下诊断日志，
 * 便于从应用内日志回溯（探测失败会回落默认档并记录原因）。
 */
@Composable
fun rememberRenderProfile(): RenderProfile {
    val context = LocalContext.current
    val performance = LocalSettings.current.displaySetting.renderPerformance
    return remember(performance, context) {
        val device = detectDeviceProfile(context.applicationContext)
        resolveRenderProfile(performance, device).also { profile ->
            RenderTierDiagnostics.logIfChanged(performance, device, profile)
        }
    }
}
