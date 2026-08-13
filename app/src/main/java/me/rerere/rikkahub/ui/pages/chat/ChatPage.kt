package me.rerere.rikkahub.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.ai.AutoTaskConfig
import me.rerere.rikkahub.ui.components.ai.AutoTaskDialog
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.FilesPicker
import me.rerere.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import me.rerere.rikkahub.ui.components.ai.readAutoTaskConfig
import me.rerere.rikkahub.ui.components.ai.useCropLauncher
import me.rerere.rikkahub.ui.components.ai.writeAutoTaskConfig
import me.rerere.rikkahub.ui.components.setting.AutoCompressDialog
import me.rerere.rikkahub.ui.components.setting.ToolOutputDialog
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Decode
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
) {
    val vm: ChatVM =
        koinViewModel(
            parameters = {
                parametersOf(id.toString())
            },
        )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    @Suppress("DEPRECATION") // LocalWindowInfo replaces this in a future Compose bump
    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes =
                files.mapNotNull { file ->
                    filesManager.getFileMimeType(file)
                }
            val parts =
                buildList {
                    localFiles.forEachIndexed { index, file ->
                        val type = contentTypes.getOrNull(index)
                        if (type?.startsWith("image/") == true) {
                            add(UIMessagePart.Image(url = file.toString()))
                        } else if (type?.startsWith("video/") == true) {
                            add(UIMessagePart.Video(url = file.toString()))
                        } else if (type?.startsWith("audio/") == true) {
                            add(UIMessagePart.Audio(url = file.toString()))
                        }
                    }
                }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                    )
                },
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                    )
                },
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }

    // 自动压缩触发确认弹窗：达到触发点时询问「是否确认压缩？」
    // 确认 → 压缩；取消 → 延后触发点（当前累计值 + 阈值，累计统计不清零）
    // 压缩完成后提示缓存前缀已重置（下一条消息缓存 miss）
    val toaster = LocalToaster.current
    val context = LocalContext.current
    LaunchedEffect(vm.compressCacheResetNotice) {
        if (vm.compressCacheResetNotice) {
            toaster.show(
                context.getString(R.string.auto_compress_cache_reset_notice),
                type = ToastType.Warning,
            )
            vm.consumeCompressCacheResetNotice()
        }
    }
    if (vm.pendingAutoCompressConfirm) {
        AlertDialog(
            onDismissRequest = { vm.cancelAutoCompress() },
            title = { Text(stringResource(R.string.auto_compress_confirm_title)) },
            text = { Text(stringResource(R.string.auto_compress_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = { vm.confirmAutoCompress() }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelAutoCompress() }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val sessionTotals by vm.sessionTotals.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    // Vault 授权（对话输入框 🔑）
    val vaultSessionManager: me.rerere.rikkahub.data.vault.VaultSessionManager = koinInject()
    val biometricBuffer: me.rerere.rikkahub.data.ai.tools.local.BiometricResultBuffer = koinInject()
    var showVaultAuthDialog by remember { mutableStateOf(false) }
    // 授权时长选择（rememberSaveable：跨弹窗开合/页面重建记忆）
    var vaultAuthForever by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var vaultAuthorized by remember { mutableStateOf(false) }
    var vaultAuthMsg by remember { mutableStateOf<String?>(null) }

    fun checkVaultAuth() {
        scope.launch {
            runCatching {
                val ws = workspaceRepository.getAll().firstOrNull() ?: return@launch
                // 读沙箱 token（LINUX 区/rootfs——授权用 importFile(LINUX) 写入，writeText 的 FILES 区沙箱不可见）
                val token =
                    runCatching { workspaceRepository.readText(ws.id, "credentials/vault-token") }.getOrDefault("")
                // 真校验：格式/过期/签名/会话存在（比只查文件存在准确——文件残留/过期 token 不再误报已授权）
                vaultAuthorized = token.isNotBlank() && vaultSessionManager.verifyToken(token)
            }
        }
    }
    fun doVaultAuthorize(ttlMs: Long) {
        scope.launch {
            me.rerere.rikkahub.data.log.AppLog.d("VaultAuth", "授权点击：ttlMs=$ttlMs")
            val ok = me.rerere.rikkahub.data.vault.VaultBiometric.authenticate(
                context, biometricBuffer, context.getString(R.string.vault_authorize_title),
            )
            if (!ok) {
                vaultAuthMsg = context.getString(R.string.vault_authorize_cancelled)
                me.rerere.rikkahub.data.log.AppLog.d("VaultAuth", "指纹取消/失败")
                return@launch
            }
            runCatching {
                val token = vaultSessionManager.issueSessionToken(ttlMs = ttlMs)
                me.rerere.rikkahub.data.log.AppLog.d("VaultAuth", "token 签发成功 len=${token.length}")
                val ws = workspaceRepository.getAll().firstOrNull() ?: error("no workspace")
                val written = workspaceRepository.writeText(ws.id, "credentials/vault-token", token, overwrite = true)
                me.rerere.rikkahub.data.log.AppLog.d("VaultAuth", "写沙箱成功 ws.id=${ws.id} path=${written.path}")
                vaultAuthorized = true
                vaultAuthMsg = context.getString(R.string.vault_authorize_success)
            }.onFailure { e ->
                me.rerere.rikkahub.data.log.AppLog.d("VaultAuth", "授权失败: ${e.message}")
                vaultAuthMsg = "授权失败: ${e.message}"
            }
        }
    }
    fun doVaultRevoke() {
        scope.launch {
            runCatching {
                vaultSessionManager.revokeAll()
                val ws = workspaceRepository.getAll().firstOrNull()
                if (ws != null) {
                    val deleted =
                        runCatching {
                            workspaceRepository.deleteFile(
                                ws.id,
                                me.rerere.workspace.WorkspaceStorageArea.FILES,
                                "credentials/vault-token",
                                false,
                            )
                        }
                    me.rerere.rikkahub.data.log.AppLog.d(
                        "VaultRevoke",
                        "deleteFile FILES 结果=${deleted.getOrNull()} 异常=${deleted.exceptionOrNull()?.message} ws.id=${ws.id} root=${ws.root}",
                    )
                } else {
                    me.rerere.rikkahub.data.log.AppLog.d("VaultRevoke", "ws 为 null——未执行 deleteFile")
                }
                vaultAuthorized = false
                vaultAuthMsg = context.getString(R.string.vault_authorize_revoked)
            }.onFailure { e ->
                me.rerere.rikkahub.data.log.AppLog.d("VaultRevoke", "撤销失败: ${e.message}")
                vaultAuthMsg = "撤销失败: ${e.message}"
            }
        }
    }
    // 进入对话页时检查沙箱授权状态（token 文件是否存在）
    LaunchedEffect(Unit) { checkVaultAuth() }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    var showAutoTaskDialog by remember { mutableStateOf(false) }
    var autoTaskConfig by remember { mutableStateOf(readAutoTaskConfig(context)) }

    val completionProviders =
        remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
            assistant.workspaceId
                ?.let { workspaceId ->
                    listOf(
                        WorkspaceCompletionProvider(
                            workspaceId = workspaceId.toString(),
                            repository = workspaceRepository,
                            currentCwd = conversation.workspaceCwd,
                        ),
                    )
                }.orEmpty()
        }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    },
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    sessionTotals = sessionTotals,
                    onToggleSearch = {
                        val current = setting.getCurrentAssistant()
                        vm.updateSettings(
                            setting.copy(
                                assistants =
                                    setting.assistants.map { assistant ->
                                        if (assistant.id == current.id) {
                                            assistant.copy(enableWebSearch = !enableWebSearch)
                                        } else {
                                            assistant
                                        }
                                    },
                            ),
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show(
                                context.getString(R.string.chat_select_model_first),
                                type = ToastType.Error,
                            )
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants =
                                    setting.assistants.map { assistant ->
                                        if (assistant.id == it.id) {
                                            it
                                        } else {
                                            assistant
                                        }
                                    },
                            ),
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index,
                            ),
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    onVaultAuthorizeClick = {
                        checkVaultAuth()
                        showVaultAuthDialog = true
                    },
                    onAutoClick = {
                        autoTaskConfig = readAutoTaskConfig(context)
                        showAutoTaskDialog = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes =
                                conversation.messageNodes.map { node ->
                                    if (node.id == newNode.id) {
                                        newNode
                                    } else {
                                        node
                                    }
                                },
                        ),
                    )
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason, scope, toolName ->
                    vm.handleToolApproval(toolCallId, approved, reason, scope, toolName)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }

        if (showAutoTaskDialog) {
            AutoTaskDialog(
                config = autoTaskConfig,
                onDismiss = { showAutoTaskDialog = false },
                onConfirm = { config ->
                    autoTaskConfig = config
                    writeAutoTaskConfig(context, config)
                    vm.scheduleAutoTask(config)
                },
                onStop = {
                    vm.cancelAutoTask()
                    writeAutoTaskConfig(context, AutoTaskConfig())
                    autoTaskConfig = AutoTaskConfig()
                },
                hasActiveTask = vm.autoTaskActive.collectAsState().value,
            )
        }

        // Vault 授权弹窗：①授权（自动签发写沙箱）②授权时间 ③跳转凭证入口
        if (showVaultAuthDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showVaultAuthDialog = false },
                title = { Text(stringResource(R.string.vault_authorize_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vaultAuthMsg?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        // 授权状态
                        Text(
                            text =
                                when {
                                    vaultAuthorized && vaultAuthForever -> stringResource(R.string.vault_authorize_status_on_forever)
                                    vaultAuthorized -> stringResource(R.string.vault_authorize_status_on)
                                    else -> stringResource(R.string.vault_authorize_status_off)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // ② 授权时间选择
                        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.material3.RadioButton(selected = !vaultAuthForever, onClick = { vaultAuthForever = false })
                                Text(stringResource(R.string.vault_authorize_short), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.material3.RadioButton(selected = vaultAuthForever, onClick = { vaultAuthForever = true })
                                Text(stringResource(R.string.vault_authorize_forever), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    // ① 主授权按钮：未授权=授权；已授权=重新授权（按当前时长选择，直接覆盖旧 token）
                    TextButton(
                        onClick = {
                            doVaultAuthorize(if (vaultAuthForever) Long.MAX_VALUE else 30L * 60 * 1000)
                            showVaultAuthDialog = false
                        },
                    ) {
                        Text(
                            stringResource(
                                if (vaultAuthorized) R.string.vault_authorize_renew else R.string.vault_authorize_confirm,
                            ),
                        )
                    }
                },
                dismissButton = {
                    Row {
                        if (vaultAuthorized) {
                            TextButton(onClick = { doVaultRevoke(); showVaultAuthDialog = false }) {
                                Text(stringResource(R.string.vault_authorize_revoke), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        // ③ 跳转凭证相关入口
                        TextButton(
                            onClick = {
                                showVaultAuthDialog = false
                                navController.navigate(me.rerere.rikkahub.Screen.Vault)
                            },
                        ) { Text(stringResource(R.string.vault_authorize_open_vault)) }
                        TextButton(onClick = { showVaultAuthDialog = false }) { Text(stringResource(R.string.settings_cancel)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showAutoCompressDialog by remember { mutableStateOf(false) }
    var showToolOutputDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        showAutoCompressDialog = false
        showToolOutputDialog = false
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
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
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
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
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
                                            context.getString(R.string.chat_input_file_read_failed, fileName),
                                            type = ToastType.Error,
                                        )
                                        return@mapNotNull null
                                    }
                            UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                        } else {
                            toaster.show(
                                context.getString(R.string.chat_input_unsupported_file_type, fileName),
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
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants =
                            setting.assistants.map { assistant ->
                                if (assistant.id == it.id) {
                                    it
                                } else {
                                    assistant
                                }
                            },
                    ),
                )
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
            showToolOutputDialog = showToolOutputDialog,
            onShowToolOutputDialogChange = { showToolOutputDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
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
                val newSettings =
                    setting.copy(
                        autoCompressEnabled = enabled,
                        autoCompressMode = mode,
                        autoCompressTokenBase = base,
                        autoCompressThreshold = threshold,
                        autoCompressTokenLimit = tokenLimit,
                    )
                vm.updateSettings(newSettings)
                // 用户重设阈值/模式 → 触发点 = 当前累计值 + 新阈值
                vm.resetAutoCompressTriggerPoint(newSettings)
            },
        )
    }
    if (showToolOutputDialog) {
        ToolOutputDialog(
            enabled = setting.toolOutputEnabled,
            maxCharsKB = setting.toolOutputMaxChars / 1000,
            onDismiss = { showToolOutputDialog = false },
            onConfirm = { enabled, maxChars ->
                vm.updateSettings(setting.copy(toolOutputEnabled = enabled, toolOutputMaxChars = maxChars * 1000))
            },
        )
    }
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onUpdateTitle: (String) -> Unit,
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
                    Icon(HugeIcons.Menu03, "Messages")
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
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank {
                                stringResource(
                                    R.string.assistant_page_default_assistant,
                                )
                            }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                ),
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                },
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                },
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
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
