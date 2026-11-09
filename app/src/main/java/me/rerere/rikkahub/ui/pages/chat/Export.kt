package me.rerere.rikkahub.ui.pages.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Image02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.showFailure
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.toLocalString
import org.koin.compose.koinInject
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }
    var includeReasoning by remember { mutableStateOf(true) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
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
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_page_export_include_reasoning)) },
                    trailingContent = { Switch(checked = includeReasoning, onCheckedChange = { includeReasoning = it }) },
                )

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        if (exportToMarkdown(context, conversation, selectedMessages, includeReasoning)) {
                            toaster.show(
                                markdownSuccessMessage,
                                type = ToastType.Success,
                            )
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        },
                    )
                }

                val textSuccessMessage = stringResource(id = R.string.chat_page_export_success, "TXT")
                OutlinedCard(
                    onClick = {
                        if (exportToText(context, conversation, selectedMessages, includeReasoning)) {
                            toaster.show(textSuccessMessage, type = ToastType.Success)
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(id = R.string.chat_page_export_text)) },
                        supportingContent = { Text(stringResource(id = R.string.chat_page_export_text_desc)) },
                        leadingContent = { Icon(HugeIcons.File02, contentDescription = null) },
                    )
                }
                // JSON 导出
                val jsonSuccessMessage = stringResource(id = R.string.chat_page_export_success, "JSON")
                OutlinedCard(
                    onClick = {
                        if (exportToJson(context, conversation)) {
                            toaster.show(jsonSuccessMessage, type = ToastType.Success)
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text("JSON") },
                        supportingContent = { Text(stringResource(R.string.export_json_desc)) },
                        leadingContent = { Icon(HugeIcons.Download01, contentDescription = null) },
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            },
                        )

                        HorizontalDivider()

                        if (includeReasoning) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.chat_page_export_image_expand_reasoning),
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = imageExportOptions.expandReasoning,
                                        onCheckedChange = {
                                            imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                        },
                                    )
                                },
                            )
                        }

                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = selectedMessages,
                                                settings = settings,
                                                options = imageExportOptions,
                                            )
                                        }.onFailure {
                                            it.printStackTrace()
                                            toaster.showFailure(context, it)
                                        }.onSuccess { exported ->
                                            if (exported) {
                                                toaster.show(
                                                    imageSuccessMessage,
                                                    type = ToastType.Success,
                                                )
                                                onDismissRequest()
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }

                // 导入
                val importRepo = koinInject<ConversationRepository>()
                val importLauncher =
                    rememberSafeLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        uri?.let { selectedUri ->
                            scope.launch {
                                try {
                                    val newConversation =
                                        withContext(Dispatchers.IO) {
                                            val jsonString =
                                                context.contentResolver
                                                    .openInputStream(selectedUri)
                                                    ?.bufferedReader()
                                                    ?.use { it.readText() }
                                            jsonString?.let {
                                                JsonInstant.decodeFromString<Conversation>(it)
                                                    .copy(id = kotlin.uuid.Uuid.random())
                                            }
                                        }
                                    if (newConversation != null) {
                                        importRepo.insertConversation(newConversation)
                                        toaster.show(context.getString(R.string.export_import_success), type = ToastType.Success)
                                        onDismissRequest()
                                    }
                                } catch (e: Exception) {
                                    toaster.show(context.getString(R.string.export_import_failed, e.message), type = ToastType.Error)
                                }
                            }
                        }
                    }
                OutlinedCard(
                    onClick = { importLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.export_import_session)) },
                        supportingContent = { Text(stringResource(R.string.export_import_desc)) },
                        leadingContent = { Icon(HugeIcons.FileImport, contentDescription = null) },
                    )
                }
            }
        }
    }
}

private fun exportToText(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    includeReasoning: Boolean = true,
): Boolean {
    val stamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val filename = "chat-export-$stamp.txt"
    val text = buildString {
        appendLine(conversation.title)
        appendLine("=".repeat(conversation.title.length.coerceAtLeast(1)))
        appendLine()
        messages.forEach { message ->
            appendLine(if (message.role == MessageRole.USER) "User:" else "Assistant:")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> appendLine(part.text)
                    is UIMessagePart.Reasoning -> if (includeReasoning) appendLine("[Reasoning] ${part.reasoning}")
                    is UIMessagePart.Image -> appendLine("[Image]")
                    is UIMessagePart.Tool -> appendLine("[Tool: ${part.toolName}]")
                    else -> Unit
                }
            }
            appendLine()
        }
    }
    return shareTempFile(context, filename, "text/plain", text.toByteArray(Charsets.UTF_8))
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Markdown 各类型分支平铺导出，与 TXT/JSON 路径逐段对照；拆开会打散同一份排布
private fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    includeReasoning: Boolean = true,
): Boolean {
    val filename = "chat-export-${LocalDateTime.now().format(
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss"),
    )}.md"

    val sb =
        buildAnnotatedString {
            append("# ${conversation.title}\n\n")
            append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

            messages.forEach { message ->
                val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
                append("$role:\n\n")
                message.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            append(part.text)
                            appendLine()
                        }

                        is UIMessagePart.Image -> {
                            append("![Image](${part.encodeBase64().getOrNull()?.base64})")
                            appendLine()
                        }

                        is UIMessagePart.Reasoning -> if (includeReasoning) {
                            part.reasoning
                                .lines()
                                .filter { it.isNotBlank() }
                                .map { "> $it" }
                                .forEach {
                                    append(it)
                                }
                            appendLine()
                            appendLine()
                        }

                        is UIMessagePart.Tool -> {
                            append("**Tool**: `${part.toolName}`")
                            appendLine()
                            if (part.toolCallId.isNotBlank()) {
                                append("- Call ID: `${part.toolCallId}`")
                                appendLine()
                            }

                            append("Input:")
                            appendLine()
                            append("```json")
                            appendLine()
                            append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                            appendLine()
                            append("```")
                            appendLine()

                            if (part.output.isNotEmpty()) {
                                append("Output:")
                                appendLine()
                                part.output.forEach { outputPart ->
                                    when (outputPart) {
                                        is UIMessagePart.Text -> {
                                            append("```text")
                                            appendLine()
                                            append(outputPart.text)
                                            appendLine()
                                            append("```")
                                            appendLine()
                                        }

                                        is UIMessagePart.Reasoning -> if (includeReasoning) {
                                            outputPart.reasoning
                                                .lines()
                                                .filter { it.isNotBlank() }
                                                .forEach {
                                                    append("> $it")
                                                    appendLine()
                                                }
                                        }

                                        is UIMessagePart.Image -> {
                                            append("![Tool Image](${outputPart.encodeBase64().getOrNull()?.base64})")
                                            appendLine()
                                        }

                                        is UIMessagePart.Document -> {
                                            append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                            appendLine()
                                        }

                                        is UIMessagePart.Video -> {
                                            append("[Video](${outputPart.url})")
                                            appendLine()
                                        }

                                        is UIMessagePart.Audio -> {
                                            append("[Audio](${outputPart.url})")
                                            appendLine()
                                        }

                                        else -> {}
                                    }
                                }
                            }
                            appendLine()
                        }

                        else -> {}
                    }
                }
                appendLine()
                append("---")
                appendLine()
            }
        }

    return shareTempFile(context, filename, "text/markdown", sb.toString().toByteArray())
}

@Suppress("TooGenericExceptionCaught") // Bitmap, FileProvider and Android share intents expose unchecked failures that must still show the export-failure toast.
private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions(),
): Boolean {
    val filename = "chat-export-${LocalDateTime.now().format(
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss"),
    )}.png"
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
    if (activity == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_failed_get_activity),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return false
    }

    val bitmap =
        composer.composableToBitmap(
            activity = activity,
            width = 540.dp,
            screenDensity = density,
            content = {
                CompositionLocalProvider(LocalSettings provides settings) {
                    ExportedChatImage(
                        conversation = conversation,
                        messages = messages,
                        options = options,
                    )
                }
            },
        )

    return try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }

        // Save to gallery (best-effort; a gallery-save failure must not abort sharing)
        runCatching { context.exportImage(activity, bitmap, filename) }
            .onFailure { it.printStackTrace() }

        // Share the file
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        shareFile(context, uri, "image/png")
        true
    } catch (e: IOException) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_failed_export_image, e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
        }
        false
    } catch (e: RuntimeException) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_failed_export_image, e.message.orEmpty()),
                Toast.LENGTH_SHORT,
            ).show()
        }
        false
    } finally {
        bitmap.recycle()
    }
}

private fun shareFile(
    context: Context,
    uri: Uri,
    mimeType: String,
) {
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via),
        ),
    )
}

/**
 * 导出会话为 JSON 文件，可用于导入恢复
 */
fun exportToJson(
    context: Context,
    conversation: Conversation,
): Boolean {
    val filename = "chat-export-${LocalDateTime.now().toLocalString()}.json"

    return try {
        val jsonString = JsonInstant.encodeToString(Conversation.serializer(), conversation)
        shareTempFile(context, filename, "application/json", jsonString.toByteArray())
    } catch (e: IllegalArgumentException) {
        // encodeToString 抛 SerializationException（IllegalArgumentException 子类）
        showExportFailure(context, e)
        false
    }
}

private fun showExportFailure(context: Context, e: Exception) {
    e.printStackTrace()
    Toast.makeText(
        context,
        context.getString(R.string.backup_page_export_failed, e.message.orEmpty()),
        Toast.LENGTH_SHORT,
    ).show()
}

// 文本类导出的公共收尾：写临时文件 + FileProvider + 分享。
// 成功返回 true；写盘或分享失败时弹失败提示并返回 false，调用方只在 true 时报成功。
private fun shareTempFile(
    context: Context,
    filename: String,
    mimeType: String,
    bytes: ByteArray,
): Boolean {
    return try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (file.exists()) {
            file.delete()
        }
        file.createNewFile()
        FileOutputStream(file).use {
            it.write(bytes)
        }

        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        shareFile(context, uri, mimeType)
        true
    } catch (e: IOException) {
        // 文件读写 IOException、FileProvider.getUriForFile 抛 IllegalArgumentException、
        // shareFile 内 startActivity 抛 ActivityNotFoundException
        showExportFailure(context, e)
        false
    } catch (e: IllegalArgumentException) {
        showExportFailure(context, e)
        false
    } catch (e: ActivityNotFoundException) {
        showExportFailure(context, e)
        false
    }
}
