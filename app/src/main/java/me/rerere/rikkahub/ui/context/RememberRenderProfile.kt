package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import me.rerere.highlight.highlightCacheCapacity
import me.rerere.rikkahub.data.perf.RenderProfile
import me.rerere.rikkahub.data.perf.resolveRenderProfileLogged

/**
 * 当前生效的渲染档位。
 *
 * 只依赖「用户偏好 + 设备能力」两个稳定输入，组合期成本可忽略；
 * 档位解析与诊断留痕统一由 [resolveRenderProfileLogged] 处理（探测失败会回落默认档并记录原因）。
 */
@Composable
fun rememberRenderProfile(): RenderProfile {
    val context = LocalContext.current
    val performance = LocalSettings.current.displaySetting.renderPerformance
    val profile =
        remember(performance, context) {
            resolveRenderProfileLogged(performance, context.applicationContext)
        }
    // 高亮缓存容量跟随档位：滚动/重组时同一段代码的复用条数（只改容量，不改高亮结果）
    LaunchedEffect(profile) { highlightCacheCapacity = profile.highlightCacheEntries }
    return profile
}
