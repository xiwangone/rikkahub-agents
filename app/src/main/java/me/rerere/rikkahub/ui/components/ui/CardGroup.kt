package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkahub.ui.theme.CustomColors

private val CardGroupCorner = 20.dp
private val CardGroupItemSpacing = 2.dp
private val CardGroupInnerCorner = 4.dp
private val CardGroupTitleHorizontalInset = 4.dp
private val CardGroupTitleVerticalInset = 8.dp
private val CardGroupPreviewHorizontalInset = 16.dp
private val DefaultCardGroupIconContainerSize = 34.dp
private val DefaultCardGroupIconGlyphSize = 18.dp

@PublishedApi
internal data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: (@Composable () -> Unit)?,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    val formContent: (@Composable ColumnScope.() -> Unit)? = null,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        headlineContent: @Composable () -> Unit,
    )

    // 表单项：标题行下方带一块自定义内容（输入框、滑块等），不传标题时整项都是自定义内容
    fun formItem(
        modifier: Modifier = Modifier,
        headlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    )
}

@PublishedApi
internal class CardGroupScopeImpl : CardGroupScope {
    @PublishedApi
    internal val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
            ),
        )
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = null,
                formContent = content,
            ),
        )
    }
}

// 带开关的项，点整行也能切换
fun CardGroupScope.switchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) = item(
    onClick =
        if (enabled) {
            { onCheckedChange(!checked) }
        } else {
            null
        },
    modifier = modifier,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
    headlineContent = headlineContent,
)

// 分组里第 index 项的形状，给不方便走 CardGroup 的列表（比如可拖拽排序的）用
fun cardGroupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) CardGroupCorner else CardGroupInnerCorner
    val bottom = if (index == count - 1) CardGroupCorner else CardGroupInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// 列表项开头带圆形底色的图标（默认收敛为 34.dp 圆底 + 18.dp 图标，适配多选项界面）
@Composable
fun CardGroupIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    containerSize: Dp = DefaultCardGroupIconContainerSize,
    iconSize: Dp = DefaultCardGroupIconGlyphSize,
) {
    Box(
        modifier =
            modifier
                .size(containerSize)
                .background(containerColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}

// 标题行右侧显示当前取值的小标签，比如滑块的数值
@Composable
fun CardGroupValue(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
@NonRestartableComposable
private fun CardGroupFormItem(
    item: CardGroupItem,
    formContent: @Composable ColumnScope.() -> Unit,
    count: Int,
    index: Int,
) {
    Surface(
        modifier = item.modifier.fillMaxWidth(),
        shape = cardGroupItemShape(index, count),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (item.headlineContent != null || item.leadingContent != null || item.trailingContent != null) {
                Row(
                    verticalAlignment = if (item.supportingContent != null) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item.leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.headlineContent?.let { headline ->
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                        }
                        item.supportingContent?.let { supporting ->
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, supporting)
                            }
                        }
                    }
                    item.trailingContent?.invoke()
                }
            }
            formContent()
        }
    }
}

@Composable
@NonRestartableComposable
private fun CardGroupListItem(
    item: CardGroupItem,
    count: Int,
    index: Int,
) {
    val isFirst = index == 0
    val isLast = index == count - 1
    val onClick = item.onClick

    val itemModifier =
        if (onClick != null) {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val topCorner by animateDpAsState(
                targetValue = if (isPressed || count == 1 || isFirst) CardGroupCorner else CardGroupInnerCorner,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            )
            val bottomCorner by animateDpAsState(
                targetValue = if (isPressed || count == 1 || isLast) CardGroupCorner else CardGroupInnerCorner,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            )
            item.modifier
                .fillMaxWidth()
                .graphicsLayer {
                    shape =
                        RoundedCornerShape(
                            topStart = topCorner,
                            topEnd = topCorner,
                            bottomStart = bottomCorner,
                            bottomEnd = bottomCorner,
                        )
                    clip = true
                }.clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
        } else {
            val staticShape = remember(index, count) { cardGroupItemShape(index, count) }
            item.modifier
                .fillMaxWidth()
                .graphicsLayer {
                    shape = staticShape
                    clip = true
                }
        }

    ListItem(
        headlineContent = item.headlineContent ?: {},
        modifier = itemModifier,
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: CustomColors.listItemColors,
    )
}

@Composable
inline fun CardGroup(
    modifier: Modifier = Modifier,
    noinline title: (@Composable () -> Unit)? = null,
    noinline onClick: (() -> Unit)? = null,
    content: @Composable CardGroupScope.() -> Unit,
) {
    // 必须使用 inline + 每次重组新建 CardGroupScopeImpl：
    // 1) 非 inline 的 @Composable CardGroup 会将 content 包装为独立 ComposableLambdaImpl 子 RestartGroup，
    //    状态变化时仅重跑子 lambda 而外层 CardGroup 因 $changed 未变直接 skipToGroupEnd() 跳过 Column 渲染；
    // 2) remember { CardGroupScopeImpl() } + items.clear() 在父级重组但子 lambda 跳过时会把已收集条目清空成空白卡片。
    val scope = CardGroupScopeImpl()
    scope.content()
    CardGroupLayout(
        modifier = modifier,
        title = title,
        onClick = onClick,
        items = scope.items,
    )
}

@PublishedApi
@Composable
@NonRestartableComposable
internal fun CardGroupLayout(
    modifier: Modifier,
    title: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    items: List<CardGroupItem>,
) {
    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
                    Box(
                        modifier =
                            Modifier
                                .padding(
                                    start = CardGroupTitleHorizontalInset,
                                    top = CardGroupTitleVerticalInset,
                                    bottom = CardGroupTitleVerticalInset,
                                )
                                .then(
                                    if (onClick != null) Modifier.clickable { onClick() } else Modifier,
                                ),
                    ) {
                        title()
                    }
                }
            }
        }
        val count = items.size
        items.fastForEachIndexed { index, item ->
            val formContent = item.formContent
            if (formContent != null) {
                CardGroupFormItem(item = item, formContent = formContent, count = count, index = index)
            } else {
                CardGroupListItem(item = item, count = count, index = index)
            }
            if (index != count - 1) {
                Spacer(modifier = Modifier.height(CardGroupItemSpacing))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = CardGroupPreviewHorizontalInset),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}
