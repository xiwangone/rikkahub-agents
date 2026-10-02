package me.rerere.rikkahub.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 饼图/环形图的切片颜色: 按切片(类别)分配, 与系列数量无关 */
internal fun ChartSpec.pieSliceColors(): List<Color> {
    val count = series.firstOrNull()?.values?.size ?: 0
    return List(count) { index -> ChartPalette[index % ChartPalette.size] }
}

/**
 * 饼图/环形图: 按 values 的占比分配扇形, 占比足够大的切片在片上标注百分比。
 * 坐标轴与刻度不参与绘制: 切片本身就是比例。
 */
@Composable
internal fun ChartPiePlot(
    spec: ChartSpec,
    modifier: Modifier = Modifier,
) {
    val values = remember(spec) {
        spec.series.firstOrNull()?.values.orEmpty().map { it.coerceAtLeast(0.0) }
    }
    val total = remember(values) { values.sum() }
    val sliceColors = remember(spec) { spec.pieSliceColors() }
    val textMeasurer = rememberTextMeasurer()
    val baseLabelStyle = MaterialTheme.typography.labelSmall

    // 全为 0 或负数时没有可绘制的比例, 由表格视图兜底
    if (total <= 0.0) return

    val donut = spec.style == ChartStyle.Donut

    Canvas(modifier = modifier) {
        val outer = min(size.width, size.height) / 2f * 0.92f
        if (outer <= 0f) return@Canvas
        // 描边以路径为中心线向内外各扩一半, 故环带 = 半径 ringRadius ± bandWidth/2;
        // 取 ringRadius = (outer + inner) / 2 才能让环带正好落在 inner ~ outer 之间
        // (否则外沿超出画布被裁、内沿压住百分比标签)
        val inner = if (donut) outer * 0.58f else 0f
        val bandWidth = outer - inner
        val ringRadius = (outer + inner) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        var startAngle = -90f

        values.forEachIndexed { index, value ->
            val sweep = (value / total * 360.0).toFloat()
            if (sweep <= 0f) return@forEachIndexed
            val color = sliceColors[index % sliceColors.size]
            if (donut) {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = bandWidth),
                )
            } else {
                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(center.x - outer, center.y - outer),
                    size = Size(outer * 2f, outer * 2f),
                )
            }

            // 占比过小的切片不标注, 避免文字互相压叠
            if (sweep >= 28.8f) {
                val mid = Math.toRadians((startAngle + sweep / 2f).toDouble())
                // 标签画在切片色块上, 取与色块形成对比的颜色(调色板固定, 不随主题变)
                val labelStyle = baseLabelStyle.copy(
                    color = if (color.luminance() > 0.5f) {
                        Color.Black.copy(alpha = 0.87f)
                    } else {
                        Color.White.copy(alpha = 0.95f)
                    }
                )
                val radius = if (donut) ringRadius else outer * 0.64f
                val label = textMeasurer.measure(
                    text = "${(value / total * 100).roundToInt()}%",
                    style = labelStyle,
                    maxLines = 1,
                )
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        center.x + (radius * cos(mid)).toFloat() - label.size.width / 2f,
                        center.y + (radius * sin(mid)).toFloat() - label.size.height / 2f,
                    ),
                )
            }
            startAngle += sweep
        }
    }
}
