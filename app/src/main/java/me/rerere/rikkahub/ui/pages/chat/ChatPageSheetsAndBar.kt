package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findChatModelFor
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.resolveAssistantFor
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ai.providerDisplayName

import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.setting.AutoCompressDialog
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.isAllowedFileType
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.log.AppLog

@Composable
internal fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onStartVoiceMode: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    // 压缩被生成阻塞时的提示：在 Composable 作用域内取好（context.getString 不随配置变化重建，Android Lint 会拦）
    val compressBlockedGeneratingText = stringResource(R.string.chat_page_compress_blocked_generating)
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showAutoCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        showAutoCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) =
        useCropLauncher(
            onCroppedImageReady = { croppedUri ->
                inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
                dismissAll()
            },
            onCleanup = {
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            },
        )
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
            if (captureSuccessful && cameraOutputUri != null) {
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                    cameraOutputFile?.delete()
                    cameraOutputFile = null
                    cameraOutputUri = null
                    dismissAll()
                } else {
                    launchCameraCrop(cameraOutputUri!!)
                }
            } else {
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            }
        }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cameraOutputFile!!,
                )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) =
        useCropLauncher(
            onCroppedImageReady = { croppedUri ->
                inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
                dismissAll()
            },
            onCleanup = {
                preCropTempFile?.delete()
                preCropTempFile = null
            },
        )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                AppLog.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted =
                            ImageUtils.isHeifImage(context, source) &&
                                ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        AppLog.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                AppLog.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents =
                    uris.mapNotNull { uri ->
                        val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                        val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                        if (isAllowedFileType(fileName, mime)) {
                            val localUri =
                                filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                                    ?: run {
                                        toaster.show(
                                            context.resources.getString(R.string.chat_input_file_read_failed, fileName),
                                            type = ToastType.Error,
                                        )
                                        return@mapNotNull null
                                    }
                            UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                        } else {
                            toaster.show(
                                context.resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                                type = ToastType.Error,
                            )
                            null
                        }
                    }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                if (vm.conversationJob.value != null) {
                    // 生成中发起压缩会与流式写入互相覆盖：先停下生成再压缩
                    CompletableDeferred(
                        Result.failure(
                            IllegalStateException(
                                compressBlockedGeneratingText,
                            ),
                        ),
                    )
                } else {
                    vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
                }
            },
            onUpdateAssistant = {
                vm.updateSettings { current ->
            current.copy(
                        assistants =
                            current.assistants.map { assistant ->
                                if (assistant.id == it.id) {
                                    it
                                } else {
                                    assistant
                                }
                            },
                    )
        }
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            showAutoCompressDialog = showAutoCompressDialog,
            onShowAutoCompressDialogChange = { showAutoCompressDialog = it },
            onDismiss = { dismissAll() },
            executionBackend = setting.executionBackend,
            onBackendChange = { backend ->
                vm.updateSettings { current ->
            current.copy(executionBackend = backend)
        }
            },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onStartVoiceMode = onStartVoiceMode?.let { start ->
                {
                    onDismiss()
                    start()
                }
            },
        )
    }

    if (showAutoCompressDialog) {
        AutoCompressDialog(
            enabled = setting.autoCompressEnabled,
            mode = setting.autoCompressMode,
            base = setting.autoCompressTokenBase,
            threshold = setting.autoCompressThreshold,
            tokenLimit = setting.autoCompressTokenLimit,
            onDismiss = { showAutoCompressDialog = false },
            onConfirm = { enabled, mode, base, threshold, tokenLimit ->
                vm.updateSettings { current ->
                    val updated = current.copy(
                        autoCompressEnabled = enabled,
                        autoCompressMode = mode,
                        autoCompressTokenBase = base,
                        autoCompressThreshold = threshold,
                        autoCompressTokenLimit = tokenLimit,
                    )
                    vm.resetAutoCompressTriggerPoint(updated)
                    updated
                }
            },
        )
    }
}

@Composable
internal fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
    conversationModelOverride: Boolean,
    onResetConversationModel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState =
        useEditState<String> {
            onUpdateTitle(it)
        }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    },
                ) {
                    Icon(
                        imageVector = HugeIcons.Menu03,
                        contentDescription = stringResource(R.string.accessibility_messages),
                    )
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.resolveAssistantFor(conversation.assistantId)
                    val model = settings.findChatModelFor(conversation.chatModelId, conversation.assistantId)
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 助手名单独一行：与模型/提供商挤同一行时，助手名会把后两者挤成省略号
                    Text(
                        text = assistant.name.ifBlank {
                            stringResource(
                                R.string.assistant_page_default_assistant,
                            )
                        },
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${model.displayName} (${providerDisplayName(provider)})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TopBarTunnelStatusBadge(settings = settings)
                }
            }
        },
        actions = {
            // 本会话用了独立模型时，就地给一个恢复入口（顶栏右上角；执行后端已收进输入框的「更多」）
            if (conversationModelOverride) {
                IconButton(
                    onClick = onResetConversationModel,
                ) {
                    Icon(
                        imageVector = HugeIcons.ArrowTurnBackward,
                        contentDescription = stringResource(R.string.chat_page_conversation_model_reset),
                    )
                }
            }
            IconButton(
                onClick = {
                    onClickMenu()
                },
            ) {
                Icon(
                    imageVector = if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                    contentDescription = stringResource(R.string.chat_page_history),
                )
            }

            IconButton(
                onClick = {
                    onNewChat()
                },
            ) {
                Icon(
                    imageVector = HugeIcons.MessageAdd01,
                    contentDescription = stringResource(R.string.chat_page_new_message),
                )
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    },
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            },
        )
    }
}

@Composable
private fun TopBarTunnelStatusBadge(
    settings: Settings,
) {
    val tunnelState by TsnetBridge.state.collectAsStateWithLifecycle()
    val network = settings.networkSetting
    val isConfigured = network.tailscaleAuthKey.isNotBlank() || network.tailscaleControlUrl.isNotBlank()
    if (tunnelState is TunnelState.Off && !isConfigured) return

    var showDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dotColor =
        when (tunnelState) {
            is TunnelState.Connected -> MaterialTheme.colorScheme.primary
            is TunnelState.Connecting, is TunnelState.Offline -> MaterialTheme.colorScheme.tertiary
            is TunnelState.Failed -> MaterialTheme.colorScheme.error
            is TunnelState.Off -> MaterialTheme.colorScheme.outline
        }
    val statusText =
        when (val s = tunnelState) {
            is TunnelState.Connected -> s.ip
            is TunnelState.Connecting -> stringResource(R.string.tunnel_status_connecting)
            is TunnelState.Offline -> stringResource(R.string.tunnel_status_offline)
            is TunnelState.Failed -> stringResource(R.string.tunnel_status_failed, s.reason)
            is TunnelState.Off -> stringResource(R.string.tunnel_status_disconnected)
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { showDialog = true }
                .padding(vertical = 1.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
        )
        Text(
            text = statusText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showDialog) {
        val controlLabel =
            network.tailscaleControlUrl.ifBlank {
                stringResource(R.string.chat_topbar_tunnel_default_control)
            }
        val hostLabel = network.tailscaleHostname.ifBlank { "rikkahub" }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.chat_topbar_tunnel_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.tunnel_status_label, statusText),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.chat_topbar_tunnel_node_info, hostLabel, controlLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (tunnelState is TunnelState.Connected || tunnelState is TunnelState.Connecting) {
                    TextButton(
                        onClick = {
                            scope.launch { TsnetBridge.stop() }
                            showDialog = false
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.stop),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}
