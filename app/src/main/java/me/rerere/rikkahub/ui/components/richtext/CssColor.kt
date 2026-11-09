package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color

// CSS rgb()/rgba() 颜色解析，供 Markdown 与 HTML 渲染共用。

internal fun parseRgbColor(rgb: String): Color? {
    val values = rgb.split(",").map { it.trim().toIntOrNull() }
    return if (values.size == 3 && values.all { it != null && it in 0..255 }) {
        Color(values[0]!!, values[1]!!, values[2]!!)
    } else {
        null
    }
}

internal fun parseRgbaColor(rgba: String): Color? {
    val values = rgba.split(",").map { it.trim() }
    if (values.size != 4) return null
    val r = values[0].toIntOrNull()
    val g = values[1].toIntOrNull()
    val b = values[2].toIntOrNull()
    val a = values[3].toFloatOrNull()
    val rgbValid = r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255
    return if (rgbValid && a != null && a in 0f..1f) Color(r!!, g!!, b!!, (a * 255).toInt()) else null
}
