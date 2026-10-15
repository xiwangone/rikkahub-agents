package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex
import org.json.JSONArray

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 *
 * [supportedLanguages] 是原生高亮器（`:highlight`）实际注册的语言名集合：预览页只给这份表里的语言
 * 上色，其余退化成转义后的纯文本 —— 与正文块同一口径，避免出现"正文有色、预览没色"或反过来。
 */
fun buildMarkdownPreviewHtml(
    context: Context,
    markdown: String,
    colorScheme: ColorScheme,
    supportedLanguages: Collection<String>,
): String {
    val htmlTemplate =
        context.assets
            .open("html/mark.html")
            .bufferedReader()
            .use { it.readText() }

    return htmlTemplate
        .replace("{{MARKDOWN_BASE64}}", markdown.base64Encode())
        .replace("{{SUPPORTED_LANGUAGES}}", JSONArray(supportedLanguages.toList()).toString())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}
