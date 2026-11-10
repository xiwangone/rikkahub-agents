package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import me.rerere.rikkahub.ui.context.rememberSafeLauncherForActivityResult
import me.rerere.rikkahub.ui.theme.CustomColors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import me.rerere.rikkahub.data.tunnel.TsnetBridge
import me.rerere.rikkahub.data.tunnel.TunnelStartConfig
import me.rerere.rikkahub.data.tunnel.TunnelState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.vault.CredentialResolver
import me.rerere.rikkahub.data.tunnel.resolveTunnelAuthKey
import me.rerere.rikkahub.data.vault.CredentialVaultRepository
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
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.NetworkSetting
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
        rememberSafeLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
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

    val ioScope = rememberCoroutineScope()
    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) =
        useCropLauncher(
            onCroppedImageReady = { croppedUri ->
                ioScope.launch {
                    val uris = withContext(Dispatchers.IO) { filesManager.createChatFilesByContents(listOf(croppedUri)) }
                    inputState.addImages(uris)
                    dismissAll()
                }
            },
            onCleanup = {
                preCropTempFile?.delete()
                preCropTempFile = null
            },
        )
    val imagePickerLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                AppLog.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    ioScope.launch {
                        val uris = withContext(Dispatchers.IO) { filesManager.createChatFilesByContents(selectedUris) }
                        inputState.addImages(uris)
                        dismissAll()
                    }
                } else if (selectedUris.size == 1) {
                    ioScope.launch {
                        val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                        runCatching {
                            val source = selectedUris.first()
                            // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                            withContext(Dispatchers.IO) {
                                val converted =
                                    ImageUtils.isHeifImage(context, source) &&
                                        ImageUtils.convertHeifToJpeg(context, source, tempFile)
                                if (!converted) {
                                    context.contentResolver.openInputStream(source)?.use { input ->
                                        tempFile.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                            preCropTempFile = tempFile
                            launchImageCrop(tempFile.toUri())
                        }.onFailure {
                            AppLog.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                            launchImageCrop(selectedUris.first())
                        }
                    }
                } else {
                    ioScope.launch {
                        val uris = withContext(Dispatchers.IO) { filesManager.createChatFilesByContents(selectedUris) }
                        inputState.addImages(uris)
                        dismissAll()
                    }
                }
            } else {
                AppLog.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberSafeLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
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

    Surface(
        color = CustomColors.topBarColors.containerColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!bigScreen) {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(
                        imageVector = HugeIcons.Menu03,
                        contentDescription = stringResource(R.string.accessibility_messages),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
                val assistant = settings.resolveAssistantFor(conversation.assistantId)
                val model = settings.findChatModelFor(conversation.chatModelId, conversation.assistantId)
                val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)

                // 行 1：标题 + 操作图标（同一 Row，天然对齐）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = {
                            if (conversation.messageNodes.isNotEmpty()) {
                                titleState.open(conversation.title)
                            } else {
                                toaster.show(editTitleWarning, type = ToastType.Warning)
                            }
                        },
                        color = Color.Transparent,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (conversationModelOverride) {
                        IconButton(onClick = onResetConversationModel) {
                            Icon(
                                imageVector = HugeIcons.ArrowTurnBackward,
                                contentDescription = stringResource(R.string.chat_page_conversation_model_reset),
                            )
                        }
                    }
                    IconButton(onClick = { onClickMenu() }) {
                        Icon(
                            imageVector = if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet,
                            contentDescription = stringResource(R.string.chat_page_history),
                        )
                    }
                    IconButton(onClick = { onNewChat() }) {
                        Icon(
                            imageVector = HugeIcons.MessageAdd01,
                            contentDescription = stringResource(R.string.chat_page_new_message),
                        )
                    }
                }

                // 行 2：助手名
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )

                // 行 3：模型名 + 隧道（同一 Row，天然对齐）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text =
                            if (model != null && provider != null) {
                                "${model.displayName} (${providerDisplayName(provider)})"
                            } else {
                                ""
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TopBarTunnelStatusBadge(settings = settings)
                }
            }
        }
    }
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
            // 固定语义色：绿=已连接 / 黄=连接中或离线 / 红=失败 / 灰=未启用
            is TunnelState.Connected -> Color(0xFF34A853)
            is TunnelState.Connecting, is TunnelState.Offline -> Color(0xFFF5A623)
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
                    .size(9.dp)
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
        // 快捷卡片：点开左上角 IP 即可直接改配置并启/停，不用跳到设置页
        val settingsStore: SettingsStore = koinInject()
        val vaultRepository: CredentialVaultRepository = koinInject()
        val context = LocalContext.current
        val credentialResolver = remember(vaultRepository) { CredentialResolver(vaultRepository) }
        val sheetScope = rememberCoroutineScope()

        var controlUrlDraft by remember(settings) { mutableStateOf(network.tailscaleControlUrl) }
        var authKeyDraft by remember(settings) { mutableStateOf(network.tailscaleAuthKey) }
        var hostnameDraft by remember(settings) { mutableStateOf(network.effectiveTunnelHostname) }
        var busy by remember { mutableStateOf(false) }

        val running = tunnelState is TunnelState.Connected ||
            tunnelState is TunnelState.Connecting ||
            tunnelState is TunnelState.Offline

        suspend fun persistConfig() {
            settingsStore.update { current ->
                current.copy(
                    networkSetting = current.networkSetting.copy(
                        tailscaleControlUrl = controlUrlDraft.trim(),
                        tailscaleAuthKey = authKeyDraft.trim(),
                        tailscaleHostname = hostnameDraft.trim().ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                    ),
                )
            }
        }

        suspend fun startTunnel() {
            busy = true
            val resolved = resolveTunnelAuthKey(credentialResolver, authKeyDraft).getOrElse {
                TsnetBridge.publish(TunnelState.Failed(it.message ?: "auth key resolve failed"))
                busy = false
                return
            }
            TsnetBridge.start(
                context = context,
                config = TunnelStartConfig(
                    controlURL = controlUrlDraft.trim(),
                    authKey = resolved,
                    hostname = hostnameDraft.trim().ifBlank { NetworkSetting.DEFAULT_TUNNEL_HOSTNAME },
                    acceptRoutes = network.tailscaleAcceptRoutes,
                    exitNode = network.tailscaleExitNode,
                    advertiseRoutes = network.tailscaleAdvertiseRoutes,
                    ephemeral = network.tailscaleEphemeral,
                    serve = network.tailscaleServe,
                ),
            )
            busy = false
        }

        ModalBottomSheet(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_topbar_tunnel_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor),
                    )
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = controlUrlDraft,
                    onValueChange = { controlUrlDraft = it },
                    label = { Text(stringResource(R.string.tunnel_control_url_label)) },
                    placeholder = { Text(stringResource(R.string.chat_topbar_tunnel_default_control)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = authKeyDraft,
                    onValueChange = { authKeyDraft = it },
                    label = { Text(stringResource(R.string.tunnel_auth_key_label)) },
                    placeholder = { Text(stringResource(R.string.tunnel_auth_key_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = hostnameDraft,
                    onValueChange = { hostnameDraft = it },
                    label = { Text(stringResource(R.string.tunnel_hostname_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            sheetScope.launch {
                                if (running) {
                                    TsnetBridge.stop()
                                } else {
                                    persistConfig()
                                    startTunnel()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) {
                        Text(stringResource(if (running) R.string.stop else R.string.tunnel_enable_title))
                    }
                    Button(
                        onClick = {
                            sheetScope.launch {
                                persistConfig()
                                showDialog = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            }
        }
    }
}
