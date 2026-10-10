package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
