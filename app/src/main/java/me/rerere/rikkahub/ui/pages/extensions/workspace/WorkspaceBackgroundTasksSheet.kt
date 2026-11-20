package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.BackgroundStatus
import org.koin.compose.koinInject

/**
 * 后台任务查看/管理 BottomSheet：本地与远程任务同一张表（仓库层按工作区类型分流，
 * 本地走本机进程表，远程走 SSH detached 进程）。
 *
 * 打开时拉一次列表；刷新、停止都是手动触发；加载/操作失败时行内显示错误，
 * 不静默吞掉。输出只显示最后 10 行——完整日志在远端日志文件里，终端里可看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBackgroundTasksSheet(
    workspaceId: String,
    onDismiss: () -> Unit,
) {
    val repository: WorkspaceRepository = koinInject()
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf<List<BackgroundStatus>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var killingId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.listBackground(workspaceId) }
                .onSuccess { tasks = it }
                .onFailure {
                    if (it is CancellationException) throw it
                    error = it.message ?: it.toString()
                }
            loading = false
        }
    }
    LaunchedEffect(workspaceId) { refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.workspace_background_tasks_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::refresh, enabled = !loading) {
                    Text(stringResource(R.string.workspace_background_task_refresh))
                }
            }
            if (loading && tasks == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val currentTasks = tasks
            when {
                currentTasks == null -> Unit
                currentTasks.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.workspace_background_tasks_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(currentTasks, key = { it.id }) { task ->
                            BackgroundTaskRow(
                                task = task,
                                killing = killingId == task.id,
                                onKill = {
                                    killingId = task.id
                                    scope.launch {
                                        runCatching { repository.killBackground(workspaceId, task.id) }
                                            .onFailure {
                                                if (it is CancellationException) throw it
                                                error = it.message ?: it.toString()
                                            }
                                        killingId = null
                                        refresh()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundTaskRow(
    task: BackgroundStatus,
    killing: Boolean,
    onKill: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = task.id,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                BackgroundTaskStatusBadge(task)
                if (task.running) {
                    TextButton(onClick = onKill, enabled = !killing) {
                        Text(stringResource(R.string.workspace_background_task_stop))
                    }
                }
            }
            Text(
                text = task.command,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.stdout.isNotBlank()) {
                Text(
                    text = stringResource(R.string.workspace_background_task_output),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = task.stdout.lines().takeLast(10).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BackgroundTaskStatusBadge(task: BackgroundStatus) {
    val (containerColor, contentColor, label) =
        if (task.running) {
            Triple(
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                stringResource(R.string.workspace_background_task_running),
            )
        } else {
            val exited = stringResource(R.string.workspace_background_task_exited)
            val labelText = task.exitCode?.let {
                "$exited · " + stringResource(R.string.workspace_background_task_exit_code, it)
            } ?: exited
            Triple(
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                labelText,
            )
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}
