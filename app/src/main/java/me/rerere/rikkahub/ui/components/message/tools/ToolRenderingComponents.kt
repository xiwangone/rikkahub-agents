package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

/** Visual status shared by registered, generic and MCP tool steps. */
enum class ToolStepStatus {
    RUNNING,
    NEEDS_APPROVAL,
    COMPLETED,
    FAILED,
    DENIED,
}

/** Pure status mapping: explicit approval state wins, then execution, then result evidence. */
internal fun resolveToolStepStatus(
    isPending: Boolean,
    isDenied: Boolean,
    isExecuted: Boolean,
    success: Boolean?,
    exitCode: String?,
    hasError: Boolean,
): ToolStepStatus =
    when {
        isDenied -> ToolStepStatus.DENIED
        isPending -> ToolStepStatus.NEEDS_APPROVAL
        !isExecuted -> ToolStepStatus.RUNNING
        success == false || (exitCode != null && exitCode != "0") || hasError -> ToolStepStatus.FAILED
        else -> ToolStepStatus.COMPLETED
    }

/** Compact status chip used in every tool step title row. */
@Composable
fun ToolStatusBadge(
    status: ToolStepStatus,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val (labelRes, containerColor, contentColor) =
        when (status) {
            ToolStepStatus.RUNNING ->
                Triple(
                    R.string.chat_server_tool_in_progress,
                    colorScheme.surfaceContainerHigh,
                    colorScheme.onSurfaceVariant,
                )
            ToolStepStatus.NEEDS_APPROVAL ->
                Triple(
                    R.string.tool_status_needs_approval,
                    colorScheme.primaryContainer,
                    colorScheme.onPrimaryContainer,
                )
            ToolStepStatus.COMPLETED ->
                Triple(
                    R.string.chat_server_tool_completed,
                    colorScheme.secondaryContainer,
                    colorScheme.onSecondaryContainer,
                )
            ToolStepStatus.FAILED ->
                Triple(
                    R.string.chat_server_tool_failed,
                    colorScheme.errorContainer,
                    colorScheme.onErrorContainer,
                )
            ToolStepStatus.DENIED ->
                Triple(
                    R.string.chat_message_tool_denied,
                    colorScheme.errorContainer,
                    colorScheme.onErrorContainer,
                )
        }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.12f)),
    ) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmallEmphasized,
            maxLines = 1,
        )
    }
}

/**
 * Shared inset surface for all inline tool summaries. Tool-specific renderers only supply content;
 * spacing, surface treatment and corner shape stay consistent across output families.
 */
@Composable
fun ToolSummarySurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
