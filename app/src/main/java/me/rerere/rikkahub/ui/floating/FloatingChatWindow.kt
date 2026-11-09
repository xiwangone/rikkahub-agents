package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.rememberToasterState
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.NoopActivityResultRegistryOwner
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
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

/**
 * Resolves the active Material3 [ColorScheme] for floating overlay surfaces, matching
 * [me.rerere.rikkahub.ui.theme.RikkahubTheme] (dynamic color, custom/preset theme, dark mode, AMOLED dark).
 */
internal fun resolveFloatingColorScheme(
    context: Context,
    settings: Settings,
    darkOverride: Boolean? = null,
    amoledOverride: Boolean? = null,
): ColorScheme {
    val rawColorMode = context.readStringPreference("colorMode", ColorMode.SYSTEM.name)
    val colorMode = ColorMode.entries.firstOrNull { it.name == rawColorMode } ?: ColorMode.SYSTEM
    val systemDark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    val dark =
        darkOverride
            ?: when (colorMode) {
                ColorMode.SYSTEM -> systemDark
                ColorMode.LIGHT -> false
                ColorMode.DARK -> true
            }
    val amoled = amoledOverride ?: context.readBooleanPreference("amoledDark", false)
    val baseScheme =
        when {
            settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else ->
                (
                    findThemeById(settings.themeId, settings.customThemes)
                        ?: findPresetTheme(settings.themeId)
                ).getColorScheme(dark)
        }
    return if (dark && amoled) {
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
        )
    } else {
        baseScheme
    }
}

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
    val amoledDarkMode by rememberAmoledDarkMode()
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
        remember(settings.themeId, settings.customThemes, settings.dynamicColor, dark, amoledDarkMode) {
            resolveFloatingColorScheme(
                context = context,
                settings = settings,
                darkOverride = dark,
                amoledOverride = amoledDarkMode,
            )
        }
    val monospaceBodySmall =
        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    // 悬浮窗是独立 Compose 树：提示条要自带一份，否则渲染带图表/表格的消息时
    // 读到 LocalToaster 的默认值会直接退出。
    val toaster = rememberToasterState()
    CompositionLocalProvider(
        // 悬浮窗没有宿主 Activity：给结果启动器一个空注册表，避免渲染带表格/代码块的消息时退出
        LocalActivityResultRegistryOwner provides NoopActivityResultRegistryOwner,
        LocalToaster provides toaster,
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
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = onOpen,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.floating_chat_open_session),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        IconButton(
                            onClick = onMinimize,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowDown01,
                                contentDescription = stringResource(R.string.floating_chat_minimize),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.floating_chat_close),
                                modifier = Modifier.size(18.dp),
                            )
                        }
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
                                .weight(1f)
                                .fillMaxWidth()
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
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.steps.forEach { block ->
                            when (block) {
                                is MessagePartBlock.ContentBlock -> {
                                    when (val part = block.part) {
                                        is UIMessagePart.Text ->
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainer,
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
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(12.dp),
                                                ) {
                                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                                        Text(
                                                            "$label · ${tool.toolName}",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        val detail =
                                                            tool.output
                                                                .filterIsInstance<UIMessagePart.Text>()
                                                                .joinToString("\n") { it.text }
                                                                .ifBlank { tool.input }
                                                        if (detail.isNotBlank()) {
                                                            Text(
                                                                detail.take(500),
                                                                style = monospaceBodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 4.dp),
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
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(12.dp),
                                                ) {
                                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                                        Text(
                                                            "${tool.toolName} · $status",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                        tool.input?.let {
                                                            Text(
                                                                it.toString().take(300),
                                                                style = monospaceBodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 4.dp),
                                                            )
                                                        }
                                                        tool.output?.let {
                                                            Text(
                                                                it.toString().take(300),
                                                                style = monospaceBodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                modifier = Modifier.padding(top = 4.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                is MessagePartBlock.ChartBlock -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                            Text(
                                                block.tool.toolName,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (block.tool.input.isNotBlank()) {
                                                Text(
                                                    block.tool.input.take(500),
                                                    style = monospaceBodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 4.dp),
                                                )
                                            }
                                            FilledTonalButton(
                                                onClick = onOpen,
                                                modifier = Modifier.padding(top = 6.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.floating_chat_open_session),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
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
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    if (tool.askUser) {
                                        FilledTonalButton(
                                            onClick = onOpen,
                                            modifier = Modifier.padding(top = 6.dp),
                                        ) {
                                            Text(
                                                stringResource(R.string.floating_chat_answer_in_conversation),
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    } else {
                                        Row(modifier = Modifier.padding(top = 6.dp)) {
                                            FilledTonalButton(
                                                onClick = { onApprove(tool) },
                                            ) {
                                                Text(
                                                    stringResource(R.string.floating_chat_approve_once),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            FilledTonalButton(
                                                onClick = { onDeny(tool) },
                                            ) {
                                                Text(
                                                    stringResource(R.string.floating_chat_deny),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (state.multiSession) {
                            FilledTonalButton(
                                onClick = onSwitch,
                            ) {
                                Text(
                                    stringResource(R.string.floating_chat_switch_session),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        FilledTonalButton(onClick = onOpen) {
                            Text(
                                stringResource(R.string.floating_chat_open_session),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (state.queuePaused) {
                            FilledTonalButton(
                                onClick = onResumeQueue,
                            ) {
                                Text(
                                    stringResource(R.string.floating_chat_resume_queue),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    Text(
                        state.queuedLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            textStyle = MaterialTheme.typography.bodySmall,
                            placeholder = {
                                Text(
                                    stringResource(R.string.floating_chat_reply_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 100.dp),
                            maxLines = 3,
                        )
                        FilledTonalButton(
                            onClick = {
                                if (draft.isNotBlank()) {
                                    onSend(draft)
                                    onDraftChange("")
                                }
                            },
                            enabled =
                                draft.isNotBlank() && state.conversationKey.isNotBlank(),
                        ) {
                            Text(
                                stringResource(R.string.floating_chat_send),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Text(
                            stringResource(R.string.floating_chat_cancel_generation),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
