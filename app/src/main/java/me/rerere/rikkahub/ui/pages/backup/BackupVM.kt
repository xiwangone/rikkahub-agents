package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.BackupNeedsPasswordException
import me.rerere.rikkahub.data.sync.BackupPasswordCipher
import me.rerere.rikkahub.data.sync.S3BackupItem
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.importer.ChatboxImporter
import me.rerere.rikkahub.data.sync.importer.CherryStudioProviderImporter
import me.rerere.rikkahub.data.sync.webdav.WebDavBackupItem
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
    private val appScope: AppScope,
    private val backupEncryptionManager: BackupEncryptionManager,
) : ViewModel() {
    val settings =
        settingsStore.settingsFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = Settings.dummy(),
        )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)
    val localBackupItems =
        MutableStateFlow(
            WebDavConfig.BackupItem.entries.filter { it.isCoreItem() },
        )

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    // ── 备份加密（口令管理，全局开关）──

    /** 当前是否开启备份加密。 */
    val backupEncryptionEnabled: Boolean
        get() = settings.value.backupEncryptionEnabled

    /** 是否已在本机记住口令。 */
    val hasBackupEncryptionPassword: Boolean
        get() = settings.value.backupEncryptionPasswordEnc.isNotBlank()

    /** 设置/修改口令并开启加密。明文口令仅在此方法内出现，随即加密入 Settings。 */
    fun setBackupEncryptionPassword(password: String) {
        viewModelScope.launch {
            val enc = BackupPasswordCipher.encrypt(password)
            settingsStore.update { s -> s.copy(backupEncryptionEnabled = true, backupEncryptionPasswordEnc = enc) }
        }
    }

    /** 开启/关闭加密开关（关闭不清口令，重开即恢复自动加密）。 */
    fun setBackupEncryptionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { s -> s.copy(backupEncryptionEnabled = enabled) }
        }
    }

    /** 关闭加密并清除记住的口令。 */
    fun clearBackupEncryption() {
        viewModelScope.launch {
            settingsStore.update { s -> s.copy(backupEncryptionEnabled = false, backupEncryptionPasswordEnc = "") }
        }
    }

    fun updateLocalBackupItems(items: List<WebDavConfig.BackupItem>) {
        localBackupItems.value = items
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value =
                        UiState.Success(
                            data =
                                webDavSync
                                    .listBackupFiles(
                                        config = settings.value.activeWebDavConfig(),
                                    ).sortedByDescending { it.lastModified },
                        ),
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.activeWebDavConfig())
    }

    // ── WebDAV 自驱动执行（AppScope，退页/切后台不取消，进程杀才停）──

    /** 测试 WebDAV 连接。返回 Result 供 UI 展示。 */
    fun runTestWebDav(onResult: (Result<Unit>) -> Unit) {
        appScope.launch {
            val r = runCatching {
                webDavSync.testConnection(settings.value.activeWebDavConfig())
                Unit
            }
            onResult(r)
        }
    }

    /** 执行云端备份（后台跑完；进程存活期间退出页面不打断）。 */
    fun runBackup(onState: (BackupRunState) -> Unit) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                webDavSync.backup(settings.value.activeWebDavConfig())
                recordBackupTime()
                loadBackupFileItems()
            }
            onState(if (r.isSuccess) BackupRunState.Success else BackupRunState.Failed(r.exceptionOrNull()))
        }
    }

    /** 执行云端恢复（完成后需重启）。 */
    fun runRestore(item: WebDavBackupItem, onState: (BackupRunState) -> Unit) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                webDavSync.restore(config = settings.value.activeWebDavConfig(), item = item)
            }
            val ex = r.exceptionOrNull()
            onState(
                when {
                    r.isSuccess -> BackupRunState.Success
                    ex is BackupNeedsPasswordException -> BackupRunState.NeedsPassword(ex.encFile)
                    else -> BackupRunState.Failed(ex)
                },
            )
        }
    }

    /** 用用户输入的口令恢复加密备份（[encFile] 为已下载 .enc，可空=重新下载）。 */
    fun runRestoreWithPassword(
        item: WebDavBackupItem,
        password: String,
        encFile: java.io.File? = null,
        onState: (BackupRunState) -> Unit,
    ) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                webDavSync.restoreWithPassword(
                    config = settings.value.activeWebDavConfig(),
                    item = item,
                    password = password,
                    cachedEncFile = encFile,
                )
            }
            val ex = r.exceptionOrNull()
            onState(
                when {
                    r.isSuccess -> BackupRunState.Success
                    // 口令错误（GCM 校验失败）→ 保持弹框提示重输
                    ex is IllegalArgumentException -> BackupRunState.NeedsPassword(encFile)
                    ex is BackupNeedsPasswordException -> BackupRunState.NeedsPassword(ex.encFile)
                    else -> BackupRunState.Failed(ex)
                },
            )
        }
    }

    /** 删除云端备份文件。 */
    fun runDeleteBackupFile(item: WebDavBackupItem, onState: (BackupRunState) -> Unit) {
        appScope.launch {
            val r = runCatching {
                webDavSync.deleteBackupFile(settings.value.activeWebDavConfig(), item)
                loadBackupFileItems()
            }
            onState(if (r.isSuccess) BackupRunState.Success else BackupRunState.Failed(r.exceptionOrNull()))
        }
    }

    suspend fun backup() {
        webDavSync.backup(settings.value.activeWebDavConfig())
        recordBackupTime()
    }

    suspend fun restore(item: WebDavBackupItem) {
        webDavSync.restore(config = settings.value.activeWebDavConfig(), item = item)
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.activeWebDavConfig(), item)
    }

    suspend fun exportToFile(): File {
        val plainFile =
            webDavSync.prepareBackupFile(
                settings.value.activeWebDavConfig().copy(items = localBackupItems.value),
            )
        val file = backupEncryptionManager.maybeEncrypt(plainFile)
        // 加密时清理明文中间产物（调用方负责删返回的 .enc；明文模式返回原文件由调用方删）
        if (file != plainFile && plainFile.exists()) {
            plainFile.delete()
        }
        recordBackupTime()
        return file
    }

    suspend fun restoreFromLocalFile(file: File) {
        webDavSync.restoreFromLocalFile(
            file,
            settings.value.activeWebDavConfig().copy(items = localBackupItems.value),
        )
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult = withContext(Dispatchers.IO) {
        val currentSettings = settings.value
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = currentSettings.assistantId,
            providers = currentSettings.providers,
            shouldImportConversation = { conversationId ->
                val exists = conversationRepository.existsConversationById(conversationId)
                if (exists) skippedExistingConversations++
                !exists
            },
            saveImage = { resource ->
                val entity = filesManager.saveUploadFromBytes(
                    bytes = resource.bytes,
                    displayName = resource.fileName,
                    mimeType = resource.mimeType,
                )
                filesManager.getFile(entity).toUri().toString()
            },
            onConversation = { conversation ->
                conversationRepository.insertConversation(conversation)
                importedConversations++
            }
        )

        val targetAssistantId = currentSettings.assistantId
        settingsStore.update { latestSettings ->
            latestSettings.copy(
                providers = result.providers + latestSettings.providers.filterNot { existing ->
                    result.providers.any { imported -> imported.id == existing.id }
                },
                assistants = latestSettings.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        }

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "import ${result.importedImageParts} images, drop ${result.skippedImageParts} images, " +
                "skip ${result.skippedForkMessages} fork messages and ${result.skippedSessions} sessions"
        )
        ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            importedImageParts = result.importedImageParts,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
            skippedForkMessages = result.skippedForkMessages,
            skippedSessions = result.skippedSessions,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            ),
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value =
                        UiState.Success(
                            data =
                                s3Sync.listBackupFiles(
                                    config = settings.value.activeS3Config(),
                                ),
                        ),
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.activeS3Config())
    }

    // ── S3 自驱动执行（AppScope，退页/切后台不取消）──

    fun runTestS3(onResult: (Result<Unit>) -> Unit) {
        appScope.launch {
            val r = runCatching {
                s3Sync.testS3(settings.value.activeS3Config())
                Unit
            }
            onResult(r)
        }
    }

    fun runBackupToS3(onState: (BackupRunState) -> Unit) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                s3Sync.backupToS3(settings.value.activeS3Config())
                recordBackupTime()
                loadS3BackupFileItems()
            }
            onState(if (r.isSuccess) BackupRunState.Success else BackupRunState.Failed(r.exceptionOrNull()))
        }
    }

    fun runRestoreFromS3(item: S3BackupItem, onState: (BackupRunState) -> Unit) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                s3Sync.restoreFromS3(config = settings.value.activeS3Config(), item = item)
            }
            val ex = r.exceptionOrNull()
            onState(
                when {
                    r.isSuccess -> BackupRunState.Success
                    ex is BackupNeedsPasswordException -> BackupRunState.NeedsPassword(ex.encFile)
                    else -> BackupRunState.Failed(ex)
                },
            )
        }
    }

    /** 用用户输入的口令恢复 S3 加密备份（[encFile] 为已下载 .enc，可空=重新下载）。 */
    fun runRestoreFromS3WithPassword(
        item: S3BackupItem,
        password: String,
        encFile: java.io.File? = null,
        onState: (BackupRunState) -> Unit,
    ) {
        appScope.launch {
            onState(BackupRunState.Running)
            val r = runCatching {
                s3Sync.restoreFromS3WithPassword(
                    config = settings.value.activeS3Config(),
                    item = item,
                    password = password,
                    cachedEncFile = encFile,
                )
            }
            val ex = r.exceptionOrNull()
            onState(
                when {
                    r.isSuccess -> BackupRunState.Success
                    ex is IllegalArgumentException -> BackupRunState.NeedsPassword(encFile)
                    ex is BackupNeedsPasswordException -> BackupRunState.NeedsPassword(ex.encFile)
                    else -> BackupRunState.Failed(ex)
                },
            )
        }
    }

    fun runDeleteS3BackupFile(item: S3BackupItem, onState: (BackupRunState) -> Unit) {
        appScope.launch {
            val r = runCatching {
                s3Sync.deleteS3BackupFile(settings.value.activeS3Config(), item)
                loadS3BackupFileItems()
            }
            onState(if (r.isSuccess) BackupRunState.Success else BackupRunState.Failed(r.exceptionOrNull()))
        }
    }

    suspend fun backupToS3() {
        s3Sync.backupToS3(settings.value.activeS3Config())
        recordBackupTime()
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        s3Sync.restoreFromS3(config = settings.value.activeS3Config(), item = item)
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.activeS3Config(), item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig =
                    settings.backupReminderConfig.copy(
                        lastBackupTime = System.currentTimeMillis(),
                    ),
            )
        }
    }
}

/** 备份/恢复/删除的一次性运行状态（UI 据此显示进度与结果）。 */
sealed class BackupRunState {
    data object Running : BackupRunState()
    data object Success : BackupRunState()
    data class Failed(val error: Throwable?) : BackupRunState()

    /** 恢复遇到加密包且本机无口令/口令错误：UI 应弹框要求输入口令后重试。 */
    data class NeedsPassword(val encFile: java.io.File?) : BackupRunState()
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val importedImageParts: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
    val skippedForkMessages: Int,
    val skippedSessions: Int,
)
