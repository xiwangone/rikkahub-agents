package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.ui.theme.JetbrainsMono

// 单个容器默认最多组合的子节点数: 大对象/大数组一次展开会组合上千个节点,
// 直接拖慢首帧与滚动; 超出部分先折叠, 点击后再继续展开
private const val INITIAL_VISIBLE_CHILDREN = 100

// 内联展示的字符串最大长度: 超长字符串单行测量昂贵, 只预览前若干字符,
// 完整内容仍可点击在底部面板查看
private const val MAX_INLINE_STRING_CHARS = 2_000

@Composable
fun JsonTree(
    json: JsonElement,
    modifier: Modifier = Modifier,
    initialExpandLevel: Int = 1,
) {
    var selectedString by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.horizontalScroll(rememberScrollState())) {
        JsonNode(
            element = json,
            key = null,
            depth = 0,
            initialExpandLevel = initialExpandLevel,
            onStringClick = { selectedString = it },
        )
    }

    selectedString?.let { content ->
        ModalBottomSheet(
            onDismissRequest = { selectedString = null },
            sheetState =
                rememberBottomSheetState(
                    initialValue = SheetValue.Hidden,
                    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                ),
        ) {
            Text(
                text = content,
                fontFamily = JetbrainsMono,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun JsonNode(
    element: JsonElement,
    key: String?,
    depth: Int,
    initialExpandLevel: Int,
    onStringClick: (String) -> Unit,
) {
    when (element) {
        is JsonObject -> JsonObjectNode(element, key, depth, initialExpandLevel, onStringClick)
        is JsonArray -> JsonArrayNode(element, key, depth, initialExpandLevel, onStringClick)
        is JsonPrimitive -> JsonPrimitiveNode(element, key, depth, onStringClick)
        is JsonNull -> JsonNullNode(key, depth)
    }
}

@Composable
private fun JsonObjectNode(
    obj: JsonObject,
    key: String?,
    depth: Int,
    initialExpandLevel: Int,
    onStringClick: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(depth < initialExpandLevel) }
    var showAllChildren by rememberSaveable(obj) { mutableStateOf(false) }
    val entries = remember(obj) { obj.entries.toList() }
    val visibleEntries = if (showAllChildren) entries else entries.take(INITIAL_VISIBLE_CHILDREN)

    Column {
        Row(
            modifier =
                Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(start = (depth * 16).dp)
                        .size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (key != null) {
                KeyText(key)
                Text(": ", fontFamily = JetbrainsMono)
            }
            Text(
                text = if (expanded) "{" else "{ ... } (${entries.size})",
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                visibleEntries.forEach { (childKey, childElement) ->
                    JsonNode(
                        element = childElement,
                        key = childKey,
                        depth = depth + 1,
                        initialExpandLevel = initialExpandLevel,
                        onStringClick = onStringClick,
                    )
                }
                if (entries.size > visibleEntries.size) {
                    Row(modifier = Modifier.padding(start = (depth * 16 + 14).dp)) {
                        Text(
                            text = "… 还有 ${entries.size - visibleEntries.size} 项（点击展开）",
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showAllChildren = true },
                        )
                    }
                }
                Row(modifier = Modifier.padding(start = (depth * 16 + 14).dp)) {
                    Text(
                        text = "}",
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonArrayNode(
    array: JsonArray,
    key: String?,
    depth: Int,
    initialExpandLevel: Int,
    onStringClick: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(depth < initialExpandLevel) }
    var showAllChildren by rememberSaveable(array) { mutableStateOf(false) }
    val visibleChildren = if (showAllChildren) array.size else minOf(array.size, INITIAL_VISIBLE_CHILDREN)

    Column {
        Row(
            modifier =
                Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(start = (depth * 16).dp)
                        .size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (key != null) {
                KeyText(key)
                Text(": ", fontFamily = JetbrainsMono)
            }
            Text(
                text = if (expanded) "[" else "[ ... ] (${array.size})",
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                array.take(visibleChildren).forEachIndexed { index, childElement ->
                    JsonNode(
                        element = childElement,
                        key = index.toString(),
                        depth = depth + 1,
                        initialExpandLevel = initialExpandLevel,
                        onStringClick = onStringClick,
                    )
                }
                if (array.size > visibleChildren) {
                    Row(modifier = Modifier.padding(start = (depth * 16 + 14).dp)) {
                        Text(
                            text = "… 还有 ${array.size - visibleChildren} 项（点击展开）",
                            fontFamily = JetbrainsMono,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showAllChildren = true },
                        )
                    }
                }
                Row(modifier = Modifier.padding(start = (depth * 16 + 14).dp)) {
                    Text(
                        text = "]",
                        fontFamily = JetbrainsMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonPrimitiveNode(
    primitive: JsonPrimitive,
    key: String?,
    depth: Int,
    onStringClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = (depth * 16 + 14).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (key != null) {
            KeyText(key)
            Text(": ", fontFamily = JetbrainsMono)
        }
        ValueText(
            primitive = primitive,
            onClick =
                if (primitive.isString) {
                    { onStringClick(primitive.contentOrNull ?: "") }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun JsonNullNode(
    key: String?,
    depth: Int,
) {
    Row(
        modifier = Modifier.padding(start = (depth * 16 + 14).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (key != null) {
            KeyText(key)
            Text(": ", fontFamily = JetbrainsMono)
        }
        Text(
            text = "null",
            fontFamily = JetbrainsMono,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun KeyText(key: String) {
    Text(
        text = "\"$key\"",
        fontFamily = JetbrainsMono,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ValueText(
    primitive: JsonPrimitive,
    onClick: (() -> Unit)? = null,
) {
    val (text, color) =
        when {
            primitive.isString -> {
                val content =
                    (primitive.contentOrNull ?: "")
                        .replace("\\", "\\\\")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")
                "\"$content\"" to Color(0xFF6A8759)
            }

            primitive.booleanOrNull != null -> {
                primitive.content to Color(0xFFCC7832)
            }

            primitive.longOrNull != null || primitive.doubleOrNull != null -> {
                primitive.content to Color(0xFF6897BB)
            }

            else -> {
                primitive.content to MaterialTheme.colorScheme.onSurface
            }
        }

    // 超长字符串只内联预览前若干字符: 单行文本的测量与绘制代价随长度线性增长
    val displayText =
        if (text.length > MAX_INLINE_STRING_CHARS) {
            text.take(MAX_INLINE_STRING_CHARS) + "…（点击查看全部）"
        } else {
            text
        }
    Text(
        text = displayText,
        fontFamily = JetbrainsMono,
        color = color,
        textDecoration = if (onClick != null) TextDecoration.Underline else null,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
