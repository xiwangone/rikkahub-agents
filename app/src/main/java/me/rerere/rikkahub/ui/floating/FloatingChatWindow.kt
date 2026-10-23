package me.rerere.rikkahub.ui.floating

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalExtendColors
import me.rerere.rikkahub.ui.theme.Typography
import me.rerere.rikkahub.ui.theme.darkExtendColors
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.findThemeById
import me.rerere.rikkahub.ui.theme.lightExtendColors

internal data class FloatingToolAction(
    val callId: String,
    val name: String,
    val input: String,
    val askUser: Boolean,
)

internal data class FloatingWindowState(
    val title: String,
    val status: String,
    val reply: UIMessage?,
    val elapsedLabel: String?,
    val steps: List<MessagePartBlock>,
    val queuedLabel: String,
    val queuePaused: Boolean,
    val multiSession: Boolean,
    val failure: String?,
    val tools: List<FloatingToolAction>,
    val conversationKey: String = "",
)

@Composable
internal fun FloatingChatWindow(
    state: FloatingWindowState,
    settings: Settings,
    draft: String,
    onDraftChange: (String) -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onSwitch: () -> Unit,
    onResumeQueue: () -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onApprove: (FloatingToolAction) -> Unit,
    onDeny: (FloatingToolAction) -> Unit,
) {
    val colorMode = rememberCurrentColorMode()
    val dark =
        when (colorMode) {
            ColorMode.SYSTEM -> isSystemInDarkTheme()
            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
    val context = LocalContext.current
    val uriHandler =
        remember(context) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    runCatching {
                        val target = Uri.parse(uri)
                        val scheme = target.scheme?.lowercase()
                        if ((scheme == "http" || scheme == "https") && !target.host.isNullOrBlank()) {
                            context.applicationContext.startActivity(
                                Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }
        }
    val appTheme =
        remember(settings.themeId, settings.customThemes, settings.dynamicColor, dark) {
            when {
                settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (dark) {
                        dynamicDarkColorScheme(
                            context,
                        )
                    } else {
                        dynamicLightColorScheme(context)
                    }
                else ->
                    (
                        findThemeById(
                            settings.themeId,
                            settings.customThemes,
                        ) ?: findPresetTheme(settings.themeId)
                    ).getColorScheme(dark)
            }
        }
    CompositionLocalProvider(
        LocalSettings provides settings,
        LocalDarkMode provides dark,
        LocalExtendColors provides if (dark) darkExtendColors() else lightExtendColors(),
        LocalUriHandler provides uriHandler,
    ) {
        MaterialExpressiveTheme(
            colorScheme = appTheme,
            typography = Typography,
            motionScheme = MotionScheme.expressive(),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(top = 8.dp),
                        )
                        TextButton(onClick = onMinimize) { Text(stringResource(R.string.floating_chat_minimize)) }
                        TextButton(onClick = onClose) { Text(stringResource(R.string.floating_chat_close)) }
                    }
                    Text(
                        state.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    state.failure?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    val scrollState = rememberScrollState()
                    Column(
                        modifier =
                            Modifier
                                .weight(
                                    1f,
                                ).fillMaxWidth()
                                .verticalScroll(scrollState)
                                .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.elapsedLabel?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.reply == null) {
                            Text(
                                stringResource(R.string.floating_chat_no_reply),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.steps.forEach { block ->
                            when (block) {
                                is MessagePartBlock.ContentBlock -> {
                                    when (val part = block.part) {
                                        is UIMessagePart.Text ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                shape = RoundedCornerShape(16.dp),
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                )
                                            }
                                        // Media cards are intentionally not embedded in this background-only surface.
                                        else -> Unit
                                    }
                                }
                                is MessagePartBlock.ThinkingBlock ->
                                    block.steps.forEach { step ->
                                        when (step) {
                                            is ThinkingStep.ReasoningStep -> {
                                                val reasoning = step.reasoning.reasoning
                                                if (reasoning.isNotBlank()) {
                                                    Surface(
                                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                        shape = RoundedCornerShape(12.dp),
                                                    ) {
                                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                                            Text(
                                                                stringResource(R.string.floating_chat_thinking),
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = MaterialTheme.colorScheme.secondary,
                                                            )
                                                            MarkdownBlock(
                                                                content = reasoning,
                                                                modifier = Modifier.padding(top = 4.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            is ThinkingStep.ToolStep -> {
                                                val tool = step.tool
                                                val label =
                                                    when {
                                                        tool.isPending && tool.toolName == "ask_user" ->
                                                            stringResource(R.string.floating_chat_needs_answer)
                                                        tool.isPending ->
                                                            stringResource(
                                                                R.string.floating_chat_tool_needs_approval,
                                                            )
                                                        else -> stringResource(R.string.floating_chat_tool_step)
                                                    }
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    shape = RoundedCornerShape(12.dp),
                                                ) {
                                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                                        Text(
                                                            "$label · ${tool.toolName}",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.secondary,
                                                        )
                                                        val detail =
                                                            tool.output
                                                                .filterIsInstance<UIMessagePart.Text>()
                                                                .joinToString("\n") { it.text }
                                                                .ifBlank { tool.input }
                                                        if (detail.isNotBlank()) {
                                                            Text(
                                                                detail.take(500),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            is ThinkingStep.ServerToolStep -> {
                                                val tool = step.tool
                                                val status =
                                                    when (tool.status) {
                                                        ServerToolStatus.IN_PROGRESS ->
                                                            stringResource(
                                                                R.string.chat_server_tool_in_progress,
                                                            )
                                                        ServerToolStatus.COMPLETED ->
                                                            stringResource(
                                                                R.string.chat_server_tool_completed,
                                                            )
                                                        ServerToolStatus.FAILED ->
                                                            stringResource(
                                                                R.string.chat_server_tool_failed,
                                                            )
                                                    }
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    shape = RoundedCornerShape(12.dp),
                                                ) {
                                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                                        Text(
                                                            "${tool.toolName} · $status",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.secondary,
                                                        )
                                                        tool.input?.let {
                                                            Text(
                                                                it.toString().take(300),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                        tool.output?.let {
                                                            Text(
                                                                it.toString().take(300),
                                                                style = MaterialTheme.typography.bodySmall,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                is MessagePartBlock.ChartBlock -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Text(block.tool.toolName, style = MaterialTheme.typography.labelMedium)
                                            if (block.tool.input.isNotBlank()) {
                                                Text(
                                                    block.tool.input.take(500),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            TextButton(onClick = onOpen) {
                                                Text(stringResource(R.string.floating_chat_open_session))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        state.reply?.usage?.let { usage ->
                            Text(
                                stringResource(
                                    R.string.floating_chat_usage,
                                    usage.promptTokens,
                                    usage.completionTokens,
                                    usage.totalTokens,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.tools.forEach { tool ->
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(
                                        if (tool.askUser) {
                                            stringResource(R.string.floating_chat_question, tool.input)
                                        } else {
                                            stringResource(R.string.floating_chat_tool_request, tool.name, tool.input)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (tool.askUser) {
                                        TextButton(
                                            onClick = onOpen,
                                        ) { Text(stringResource(R.string.floating_chat_answer_in_conversation)) }
                                    } else {
                                        Row {
                                            Button(
                                                onClick = { onApprove(tool) },
                                            ) { Text(stringResource(R.string.floating_chat_approve_once)) }
                                            Spacer(Modifier.width(6.dp))
                                            TextButton(
                                                onClick = { onDeny(tool) },
                                            ) { Text(stringResource(R.string.floating_chat_deny)) }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.multiSession) {
                            TextButton(
                                onClick = onSwitch,
                            ) { Text(stringResource(R.string.floating_chat_switch_session)) }
                        }
                        TextButton(onClick = onOpen) { Text(stringResource(R.string.floating_chat_open_session)) }
                        if (state.queuePaused) {
                            TextButton(
                                onClick = onResumeQueue,
                            ) { Text(stringResource(R.string.floating_chat_resume_queue)) }
                        }
                    }
                    Text(
                        state.queuedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = draft, onValueChange = onDraftChange, placeholder = {
                            Text(stringResource(R.string.floating_chat_reply_hint))
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 100.dp), maxLines = 3)
                        Button(
                            onClick = {
                                if (draft.isNotBlank()) {
                                    onSend(draft)
                                    onDraftChange("")
                                }
                            },
                            enabled =
                                draft.isNotBlank() && state.conversationKey.isNotBlank(),
                        ) { Text(stringResource(R.string.floating_chat_send)) }
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.floating_chat_cancel_generation))
                    }
                }
            }
        }
    }
}
