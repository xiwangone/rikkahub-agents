package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.ui.context.rememberRenderProfile

internal val DiffAddedColor = Color(0xFF4CAF50)
internal val DiffRemovedColor = Color(0xFFEF5350)

// 单行超过该字符数即截断显示: 超长单行(如单行 JSON/转义内容)会让横向滚动容器的
// 测量宽度超出 Constraints 可表示上限导致崩溃, 且固有测量代价高昂
private const val MAX_LINE_CHARS = 4000

// 默认最多渲染的行数由当前渲染档位决定（见 data/perf/RenderProfile.kt）: 每个 diff 行
// 都是一个独立 Text 节点, 大 diff 全量渲染会让消息列表的滚动与重组明显变慢;
// 超出部分折叠为可点击展开的提示

/** unified diff 的增删行数统计 */
internal data class DiffStats(
    val additions: Int,
    val deletions: Int,
)

internal fun parseDiffStats(diff: String): DiffStats {
    var additions = 0
    var deletions = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+++") || line.startsWith("---") -> {}

            line.startsWith("+") -> {
                additions++
            }

            line.startsWith("-") -> {
                deletions++
            }
        }
    }
    return DiffStats(additions, deletions)
}

/**
 * 渲染 unified diff 文本, 按行前缀着色, 支持横向滚动; 纵向滚动由调用方容器提供
 *
 * @param maxLines 默认最多渲染的行数; 传 null 时采用当前渲染档位的默认值,
 *   超出部分折叠为一行可点击展开的提示
 * @param showFileHeader 是否渲染开头的 `---`/`+++` 文件头
 */
@Composable
fun DiffView(
    diff: String,
    modifier: Modifier = Modifier,
    maxLines: Int? = null,
    showFileHeader: Boolean = true,
) {
    // 默认限行数来自渲染档位（完整优先档为不限行）；调用方可显式传入覆盖
    val renderProfile = rememberRenderProfile()
    val effectiveMaxLines = maxLines ?: renderProfile.diffDefaultLines
    val allLines =
        remember(diff, showFileHeader) {
            val lines = diff.lines()
            if (!showFileHeader && lines.size >= 2 &&
                lines[0].startsWith("---") && lines[1].startsWith("+++")
            ) {
                lines.drop(2)
            } else {
                lines
            }
        }
    // 大 diff 默认只渲染前 maxLines 行; 用户主动展开后才渲染全部
    var showAll by remember(diff) { mutableStateOf(false) }
    val limit = if (showAll) Int.MAX_VALUE else effectiveMaxLines
    val lines =
        remember(allLines, limit) {
            allLines.take(limit).map { line ->
                if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + "…" else line
            }
        }
    val truncated = allLines.size - lines.size

    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
    ) {
        lines.fastForEach { line ->
            DiffLine(line)
        }
        if (truncated > 0) {
            Text(
                text = "… +$truncated lines（点击展开全部）",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier =
                    Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { showAll = true },
            )
        }
    }
}

@Composable
private fun DiffLine(line: String) {
    val (textColor, background) =
        when {
            line.startsWith("+++") || line.startsWith("---") -> {
                MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
            }

            line.startsWith("@@") -> {
                MaterialTheme.colorScheme.primary to Color.Transparent
            }

            line.startsWith("+") -> {
                DiffAddedColor to DiffAddedColor.copy(alpha = 0.12f)
            }

            line.startsWith("-") -> {
                DiffRemovedColor to DiffRemovedColor.copy(alpha = 0.12f)
            }

            else -> {
                MaterialTheme.colorScheme.onSurface to Color.Transparent
            }
        }
    Text(
        text = line.ifEmpty { " " },
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        softWrap = false,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .padding(horizontal = 8.dp),
    )
}
