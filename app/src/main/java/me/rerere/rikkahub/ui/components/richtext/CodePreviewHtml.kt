package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import me.rerere.highlight.CodeHighlighter
import me.rerere.highlight.HighlightTextColorPalette
import me.rerere.highlight.HighlightToken
import me.rerere.highlight.highlightScopeStyle
import me.rerere.rikkahub.utils.toCssHex

/**
 * 预览页底色，由调用方按当前主题取，与正文代码块同源：
 * 正文块底色 = `MaterialTheme.colorScheme.surfaceContainer`，正文文字 = `palette.fallback`（= `onSurface`）。
 */
data class CodePreviewTheme(
    val background: Color,
    val foreground: Color,
    val outline: Color,
)

/** 块内预览用：与原实现逐字一致，只渲染、不叠面板 */
internal fun buildCodeRenderHtml(
    code: String,
    language: String,
): String =
    if (language.lowercase() == "svg") {
        """<!DOCTYPE html><html><body style="margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;">$code</body></html>"""
    } else {
        code
    }

/**
 * 代码块「眼睛」打开的全屏预览页：渲染结果（同 [buildCodeRenderHtml]）+ 一层可折叠的高亮源码面板。
 *
 * 面板里的代码用 `:highlight` 模块的分词结果着色 —— 与正文块同一套 token、同一处 scope→颜色解析
 * （[highlightScopeStyle]）、同一个 [palette]，所以两处配色必然一致。
 * 产物是自包含的 HTML 字符串，不引外链脚本/样式，离线渲染。[sourceLabel] 为面板标题（调用方按当前
 * 语言环境取好的文案，本函数不硬编码任何界面文字）。
 */
internal fun buildCodePreviewHtml(
    code: String,
    language: String,
    highlighter: CodeHighlighter,
    palette: HighlightTextColorPalette,
    theme: CodePreviewTheme,
    sourceLabel: String,
): String {
    val sourcePanel = sourcePanelHtml(
        code = code,
        language = language,
        highlighter = highlighter,
        palette = palette,
        theme = theme,
        sourceLabel = sourceLabel,
    )
    return when (language.lowercase()) {
        "svg" -> {
            // position:fixed 的面板不参与 flex 布局，不会把居中图形挤偏
            val bodyStyle = "margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;"
            """<!DOCTYPE html><html><body style="$bodyStyle">$sourcePanel$code</body></html>"""
        }

        else -> {
            // 正文直接是用户文档，doctype 必须由本函数先发：用户文档里的 <!DOCTYPE> 落在中间会被忽略，
            // 页面掉进 quirks mode。用户自带的 <html>/<head>/<body> 标签由解析器并入现有元素。
            "<!DOCTYPE html><html><head>" +
                "<meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
                "<body>$sourcePanel$code</body></html>"
        }
    }
}

/**
 * 折叠面板：默认收起，看到的仍是渲染结果；展开是带高亮的源码。
 *
 * 用 `<details>` 而不是脚本：预览页里跑的是用户 HTML，能不依赖 JS 就不依赖。
 * 样式全走内联 `style` 并带 `!important`，避免用户文档的 CSS 把面板样式吃掉。
 */
private fun sourcePanelHtml(
    code: String,
    language: String,
    highlighter: CodeHighlighter,
    palette: HighlightTextColorPalette,
    theme: CodePreviewTheme,
    sourceLabel: String,
): String {
    val bg = theme.background.toCssHex()
    val fg = theme.foreground.toCssHex()
    val outline = theme.outline.toCssHex()
    val highlighted = highlightSourceHtml(code, language, highlighter, palette)
    return buildString {
        append(
            "<details style=\"position:fixed;top:8px;left:8px;right:8px;z-index:2147483647;" +
                "margin:0;padding:0;border:1px solid $outline;border-radius:10px;" +
                "background:$bg !important;color:$fg !important;" +
                "font-family:monospace;box-shadow:0 4px 14px rgba(0,0,0,.35);\">",
        )
        append(
            "<summary style=\"padding:8px 12px;font-size:13px;line-height:1.4;" +
                "color:$fg !important;cursor:pointer;\">",
        )
        append(escapeHtml(sourceLabel))
        append(" · ")
        append(escapeHtml(language))
        append("</summary>")
        append(
            "<pre style=\"margin:0;padding:12px;max-height:60vh;overflow:auto;white-space:pre;" +
                "background:$bg !important;color:$fg !important;font-size:12px;line-height:1.5;\">",
        )
        append("<code>")
        append(highlighted)
        append("</code></pre></details>")
    }
}

/**
 * 高亮源码 → HTML：先分词，**每个 token 的文本各自转义**，再把 `<span>` 包在转义结果外面。
 *
 * 顺序不能反：先插标签再整体转义，会连自家 `<span>` 一起转义成可见文本；先整体转义再按偏移插
 * 标签，偏移量要对齐转义后的文本，极易错位。token 互不重叠、拼接后恰好等于原文，所以逐 token
 * 转义与整体转义等价，而标签始终留在转义范围之外。
 */
private fun highlightSourceHtml(
    code: String,
    language: String,
    highlighter: CodeHighlighter,
    palette: HighlightTextColorPalette,
): String {
    val tokens = highlighter.highlight(code, language)
    return buildString {
        tokens.forEach { token ->
            val text = escapeHtml(token.content)
            if (token is HighlightToken.Styled) {
                append("<span style=\"").append(highlightScopeStyle(token.type, palette).toCssText()).append("\">")
                append(text)
                append("</span>")
            } else {
                append(text)
            }
        }
    }
}

/**
 * SpanStyle → CSS 声明。只搬正文块真正用到的三个属性（颜色、斜体、加粗），
 * 颜色表本身不在这里复制一份：解析仍由 [highlightScopeStyle] 单点负责。
 */
private fun SpanStyle.toCssText(): String =
    buildString {
        append("color:")
        append(color.toCssHex())
        if (fontStyle == FontStyle.Italic) append(";font-style:italic")
        if (fontWeight == FontWeight.Bold) append(";font-weight:bold")
    }

/** HTML 文本转义；`&` 必须最先替换，否则后面补上的实体符号会被二次转义。 */
private val HTML_ESCAPES = listOf(
    "&" to "&amp;",
    "<" to "&lt;",
    ">" to "&gt;",
    "\"" to "&quot;",
    "'" to "&#39;",
)

private fun escapeHtml(text: String): String {
    var result = text
    for ((from, to) in HTML_ESCAPES) {
        result = result.replace(from, to)
    }
    return result
}
