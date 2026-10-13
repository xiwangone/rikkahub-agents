package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.rerere.highlight.HighlightTextColorPalette

/**
 * Builds syntax colors from Material 3 semantic roles so code remains legible in both schemes.
 * Keeping this mapping non-composable preserves the current renderer API and cache behavior.
 */
private fun codeColorPalette(scheme: ColorScheme) =
    HighlightTextColorPalette(
        keyword = scheme.primary,
        string = scheme.tertiary,
        number = scheme.secondary,
        comment = scheme.onSurfaceVariant,
        function = scheme.primary,
        operator = scheme.secondary,
        punctuation = scheme.onSurface,
        className = scheme.tertiary,
        property = scheme.error,
        boolean = scheme.secondary,
        variable = scheme.error,
        tag = scheme.error,
        attrName = scheme.secondary,
        attrValue = scheme.tertiary,
        fallback = scheme.onSurface,
    )

// Legacy names remain until their renderer call sites can be migrated outside this batch.
val AtomOneDarkPalette = codeColorPalette(darkColorScheme())
val AtomOneLightPalette = codeColorPalette(lightColorScheme())

/**
 * 聊天正文代码高亮所用调色板：跟随当前主题（含动态取色）。
 *
 * 旧的全局 val 用默认 M3 配色构建，进程内只有一份，自定义主题/动态取色下
 * 代码高亮与界面完全脱节；这里按当前 colorScheme 构建并按 scheme 记忆，
 * 主题切换时随组合失效自动重建。
 */
@Composable
fun rememberCodeColorPalette(): HighlightTextColorPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { codeColorPalette(scheme) }
}
