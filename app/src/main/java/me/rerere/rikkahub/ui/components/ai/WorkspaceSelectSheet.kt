package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.pages.extensions.workspace.statusLabel

@Composable
internal fun WorkspaceSelectSheet(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: (() -> Unit)? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_select),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier =
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 不绑定
                WorkspaceSelectRow(
                    title = stringResource(R.string.workspace_no_binding),
                    selected = assistant.workspaceId == null,
                    onClick = { onSelect(null) },
                )
                workspaces.forEach { workspace ->
                    WorkspaceSelectRow(
                        title = workspace.name,
                        status = workspace.statusLabel(),
                        // 行图标按类型区分，与工作区列表页同口径：SSH 用服务器、本地用文件。
                        // 以前全行都是同一个 Codesandbox，同名工作区分不清本地还是 SSH。
                        icon = if (workspace.isSsh()) HugeIcons.ServerStack01 else HugeIcons.File02,
                        selected = workspace.id == assistant.workspaceId?.toString(),
                        onClick = { onSelect(workspace.id) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 新建工作区：直达创建（管理页打开即弹出类型选择），不用先退出、
            // 去管理页新建、再回来重选。
            if (onCreate != null) {
                ListItem(
                    leadingContent = {
                        Icon(HugeIcons.Add01, contentDescription = null)
                    },
                    headlineContent = {
                        Text(stringResource(R.string.workspace_page_create))
                    },
                    trailingContent = {
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.large)
                            .clickable { onCreate() },
                )
            }

            // 管理工作区
            ListItem(
                leadingContent = {
                    Icon(HugeIcons.Codesandbox, contentDescription = null)
                },
                headlineContent = {
                    Text(stringResource(R.string.workspace_manage))
                },
                trailingContent = {
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier =
                    Modifier
                        .clip(MaterialTheme.shapes.large)
                        .clickable { onManage() },
            )
        }
    }
}

@Composable
private fun WorkspaceSelectRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    status: String? = null,
    icon: ImageVector = HugeIcons.Codesandbox,
) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null)
        },
        headlineContent = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent =
            status?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        trailingContent =
            if (selected) {
                {
                    Icon(
                        imageVector = HugeIcons.Tick02,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                null
            },
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
            ),
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable { onClick() },
    )
}
