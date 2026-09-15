package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 弹窗内容默认高度上限：超过即滚动。 */
val DefaultDialogContentMaxHeight: Dp = 420.dp

/**
 * 弹窗内容容器：**限高 + 内部滚动 + 键盘避让**。
 *
 * 为什么统一：弹窗内容一高，底部按钮就会被顶出屏幕，用户会以为界面卡住了
 * （小屏、浅色键盘弹出时尤其明显）。统一用本容器后，各弹窗不必各写一遍
 * `heightIn` / `verticalScroll` / `imePadding`，口径也不会各不相同。
 *
 * ⚠ 内容里**不要再嵌套 LazyColumn / LazyRow**：嵌套可滚动组件会因无限高度约束抛异常。
 * 需要在弹窗里放列表时，请用 [CappedLazyColumn]，或把整段内容都作为其 item 放入。
 */
@Composable
fun ScrollableDialogContent(
    modifier: Modifier = Modifier,
    maxHeight: Dp = DefaultDialogContentMaxHeight,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 弹窗内的列表容器：**限高 + 惰性滚动**。
 *
 * 替代"每个弹窗自己写 LazyColumn + heightIn(max = …)"的重复写法，保证限高口径一致。
 */
@Composable
fun CappedLazyColumn(
    modifier: Modifier = Modifier,
    maxHeight: Dp = DefaultDialogContentMaxHeight,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        contentPadding = contentPadding,
        content = content,
    )
}
