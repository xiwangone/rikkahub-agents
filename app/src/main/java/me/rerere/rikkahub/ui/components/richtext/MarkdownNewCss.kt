package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.flow.map
import org.jsoup.nodes.Element


internal fun SpanStyle.asTextStyle(): TextStyle =
    TextStyle(
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        background = background,
        textDecoration = textDecoration,
    )

internal fun buildFontTagStyle(
    element: Element,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val color = element.attr("color").takeIf { it.isNotBlank() }?.let(::parseColor)
    val styleAttr =
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            parseInlineSpanStyle(
                style = it,
                density = density,
                baseFontSize = baseFontSize,
            )
        }
    val sizeAttr =
        element.attr("size").takeIf { it.isNotBlank() }?.let {
            parseLegacyFontSize(
                fontSize = it,
                density = density,
                baseFontSize = baseFontSize,
            )
        }

    var resolvedStyle = styleAttr ?: SpanStyle()
    color?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(color = it)) }
    sizeAttr?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(fontSize = it)) }

    return resolvedStyle.takeIf {
        color != null || styleAttr != null || sizeAttr != null
    }
}

internal fun parseInlineSpanStyle(
    style: String,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val properties = parseCssDeclarations(style)

    var hasStyle = false
    var spanStyle = SpanStyle()

    properties["color"]?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(color = it))
            hasStyle = true
        }
    }

    properties["font-weight"]?.let { value ->
        parseFontWeight(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontWeight = it))
            hasStyle = true
        }
    }

    properties["font-style"]?.let { value ->
        parseFontStyle(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontStyle = it))
            hasStyle = true
        }
    }

    properties["font-family"]?.let { value ->
        parseFontFamily(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontFamily = it))
            hasStyle = true
        }
    }

    properties["font-size"]?.let { value ->
        parseFontSize(
            fontSize = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontSize = it))
            hasStyle = true
        }
    }

    properties["letter-spacing"]?.let { value ->
        parseSpacing(
            spacing = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(letterSpacing = it))
            hasStyle = true
        }
    }

    properties["text-decoration"]?.let { value ->
        parseTextDecoration(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(textDecoration = it))
            hasStyle = true
        }
    }

    val backgroundValue = properties["background-color"] ?: properties["background"]
    backgroundValue?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(background = it))
            hasStyle = true
        }
    }

    return spanStyle.takeIf { hasStyle }
}

internal fun parseBlockTextStyle(
    style: String,
    density: Density,
    baseTextStyle: TextStyle,
): TextStyle? {
    val properties = parseCssDeclarations(style)

    val inlineStyle =
        parseInlineSpanStyle(
            style = style,
            density = density,
            baseFontSize = baseTextStyle.fontSize,
        )

    var hasStyle = inlineStyle != null
    var textStyle =
        TextStyle(
            color = inlineStyle?.color ?: Color.Unspecified,
            fontSize = inlineStyle?.fontSize ?: TextUnit.Unspecified,
            fontWeight = inlineStyle?.fontWeight,
            fontStyle = inlineStyle?.fontStyle,
            fontFamily = inlineStyle?.fontFamily,
            letterSpacing = inlineStyle?.letterSpacing ?: TextUnit.Unspecified,
            background = inlineStyle?.background ?: Color.Unspecified,
            textDecoration = inlineStyle?.textDecoration,
        )

    properties["line-height"]?.let { value ->
        parseLineHeight(
            lineHeight = value,
            density = density,
            baseFontSize = baseTextStyle.fontSize,
        )?.let {
            textStyle = textStyle.merge(TextStyle(lineHeight = it))
            hasStyle = true
        }
    }

    properties["text-align"]?.let { value ->
        parseTextAlign(value)?.let {
            textStyle = textStyle.merge(TextStyle(textAlign = it))
            hasStyle = true
        }
    }

    return textStyle.takeIf { hasStyle }
}

internal fun parseCssDeclarations(style: String): Map<String, String> =
    style
        .split(";")
        .mapNotNull { property ->
            val parts = property.split(":", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
        }.toMap()

private fun scaleBaseFontSize(baseFontSize: TextUnit, multiplier: Float): TextUnit? {
    if (!baseFontSize.isSpecified) return null
    return when (baseFontSize.type) {
        TextUnitType.Sp -> (baseFontSize.value * multiplier).sp
        TextUnitType.Em -> (baseFontSize.value * multiplier).em
        else -> null
    }
}

private val FONT_SIZE_KEYWORDS = mapOf(
    "xx-small" to 0.6f,
    "x-small" to 0.75f,
    "small" to 0.89f,
    "medium" to 1f,
    "large" to 1.2f,
    "x-large" to 1.5f,
    "xx-large" to 2f,
    "smaller" to 0.833f,
    "larger" to 1.2f,
)

private fun parseFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim().lowercase()
    if (normalized.isEmpty()) return null
    FONT_SIZE_KEYWORDS[normalized]?.let { return scaleBaseFontSize(baseFontSize, it) }
    return parseSpacing(normalized, density, baseFontSize)
}

internal fun parseSpacing(
    spacing: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = spacing.trim().lowercase()
    if (normalized.isEmpty()) return null
    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.let { with(density) { it.toSp() } }
        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.em
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let {
            if (baseFontSize.isSpecified && baseFontSize.type == TextUnitType.Sp) (baseFontSize.value * it).sp else 16.sp * it
        }
        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let {
            scaleBaseFontSize(baseFontSize, it / 100f)
        }
        else -> normalized.toFloatOrNull()?.let { with(density) { it.toSp() } }
    }
}

private fun parseLineHeight(
    lineHeight: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = lineHeight.trim().lowercase()
    if (normalized.isEmpty()) return null

    if (normalized.matches(Regex("[0-9]*\\.?[0-9]+"))) {
        if (!baseFontSize.isSpecified) return null
        return when (baseFontSize.type) {
            TextUnitType.Sp -> (baseFontSize.value * normalized.toFloat()).sp
            TextUnitType.Em -> (baseFontSize.value * normalized.toFloat()).em
            else -> null
        }
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

private fun parseLegacyFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim()
    val legacyScale =
        when (normalized) {
            "1" -> 0.625f
            "2" -> 0.8125f
            "3" -> 1f
            "4" -> 1.125f
            "5" -> 1.5f
            "6" -> 2f
            "7" -> 3f
            else -> null
        }
    if (legacyScale != null) {
        return parseFontSize(
            fontSize = "${legacyScale * 100}%",
            density = density,
            baseFontSize = if (baseFontSize.isSpecified) baseFontSize else 16.sp,
        )
    }

    if ((normalized.startsWith("+") || normalized.startsWith("-")) && baseFontSize.isSpecified) {
        val delta = normalized.toIntOrNull() ?: return null
        val adjustedLevel = (3 + delta).coerceIn(1, 7)
        return parseLegacyFontSize(
            fontSize = adjustedLevel.toString(),
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

private fun parseFontFamily(fontFamily: String): FontFamily? {
    val normalized =
        fontFamily
            .split(",")
            .map { it.trim().trim('"', '\'').lowercase() }
            .firstOrNull()
            ?: return null

    return when {
        normalized.contains("mono") || normalized.contains("courier") -> FontFamily.Monospace

        normalized.contains(
            "serif",
        ) || normalized.contains("georgia") || normalized.contains("times") -> FontFamily.Serif

        normalized.contains(
            "sans",
        ) || normalized.contains("arial") || normalized.contains("helvetica") -> FontFamily.SansSerif

        normalized.contains("cursive") -> FontFamily.Cursive

        else -> null
    }
}

private fun parseColor(colorString: String): Color? =
    try {
        when {
            colorString.startsWith("#") -> parseHexColor(colorString.removePrefix("#"))
            colorString.startsWith("rgb(") -> parseRgbColor(colorString.removePrefix("rgb(").removeSuffix(")"))
            colorString.startsWith("rgba(") -> parseRgbaColor(colorString.removePrefix("rgba(").removeSuffix(")"))
            else -> NAMED_CSS_COLORS[colorString.lowercase()]
        }
    } catch (_: IllegalArgumentException) {
        null
    }

private fun parseHexColor(hex: String): Color? =
    when (hex.length) {
        6 -> Color("#$hex".toColorInt())
        3 -> {
            val r = hex[0].toString().repeat(2)
            val g = hex[1].toString().repeat(2)
            val b = hex[2].toString().repeat(2)
            Color("#$r$g$b".toColorInt())
        }
        else -> null
    }

private fun parseRgbColor(rgb: String): Color? {
    val values = rgb.split(",").map { it.trim().toIntOrNull() }
    return if (values.size == 3 && values.all { it != null && it in 0..255 }) {
        Color(values[0]!!, values[1]!!, values[2]!!)
    } else {
        null
    }
}

private fun parseRgbaColor(rgba: String): Color? {
    val values = rgba.split(",").map { it.trim() }
    if (values.size != 4) return null
    val r = values[0].toIntOrNull()
    val g = values[1].toIntOrNull()
    val b = values[2].toIntOrNull()
    val a = values[3].toFloatOrNull()
    val rgbValid = r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255
    return if (rgbValid && a != null && a in 0f..1f) Color(r!!, g!!, b!!, (a * 255).toInt()) else null
}

private val NAMED_CSS_COLORS = mapOf(
    "red" to Color.Red,
    "green" to Color.Green,
    "blue" to Color.Blue,
    "black" to Color.Black,
    "white" to Color.White,
    "gray" to Color.Gray,
    "grey" to Color.Gray,
    "yellow" to Color.Yellow,
    "cyan" to Color.Cyan,
    "magenta" to Color.Magenta,
    "orange" to Color(0xFFFFA500),
    "purple" to Color(0xFF800080),
    "brown" to Color(0xFFA52A2A),
    "pink" to Color(0xFFFFC0CB),
)

private fun parseFontWeight(weightString: String): FontWeight? =
    when (weightString.lowercase()) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.Bold
        "bolder" -> FontWeight.ExtraBold
        "lighter" -> FontWeight.Light
        "100" -> FontWeight.W100
        "200" -> FontWeight.W200
        "300" -> FontWeight.W300
        "400" -> FontWeight.W400
        "500" -> FontWeight.W500
        "600" -> FontWeight.W600
        "700" -> FontWeight.W700
        "800" -> FontWeight.W800
        "900" -> FontWeight.W900
        else -> null
    }

private fun parseFontStyle(fontStyle: String): FontStyle? =
    when (fontStyle.lowercase()) {
        "italic", "oblique" -> FontStyle.Italic
        "normal" -> FontStyle.Normal
        else -> null
    }

private fun parseTextDecoration(textDecoration: String): TextDecoration? {
    val parts = textDecoration.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return null

    val decorations =
        buildList {
            if ("underline" in parts) add(TextDecoration.Underline)
            if ("line-through" in parts) add(TextDecoration.LineThrough)
        }

    return when (decorations.size) {
        0 -> null
        1 -> decorations.first()
        else -> TextDecoration.combine(decorations)
    }
}

private fun parseTextAlign(textAlign: String): TextAlign? =
    when (textAlign.trim().lowercase()) {
        "left", "start" -> TextAlign.Start
        "right", "end" -> TextAlign.End
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        else -> null
    }
