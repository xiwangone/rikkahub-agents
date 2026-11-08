package me.rerere.rikkahub.data.datastore

import me.rerere.workspace.WorkspaceShellBackend

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.AppScope
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.costguards.LifetimeUsage
import me.rerere.rikkahub.costguards.MessageUsageSnapshot
import me.rerere.rikkahub.costguards.accumulateLifetimeUsage
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.log.AppLog

private const val TAG = "PreferencesStore"
private const val SETTINGS_READ_RETRIES = 3

/** 消费者等待设置就绪的上限；初始化永久失败时避免协程无限挂起 */
private const val SETTINGS_READY_TIMEOUT_MS = 20_000L

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
    /** Defaults to the app-scoped store; injection permits isolated host-side persistence tests. */
    private val dataStore: DataStore<Preferences> = context.settingsStore,
) : KoinComponent {
    private val appContext: Context = context.applicationContext ?: context
    @Volatile
    private var hasCompletedInitialDecode = false
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // 自动压缩与响应重试（与既有配置合并；V4 迁移引用）
        val ENABLE_AUTO_COMPACTION = booleanPreferencesKey("enable_auto_compaction")
        val AUTO_COMPACTION_THRESHOLD_MODE = stringPreferencesKey("auto_compaction_threshold_mode")
        val AUTO_COMPACTION_THRESHOLD_PERCENT = intPreferencesKey("auto_compaction_threshold_percent")
        val AUTO_COMPACTION_THRESHOLD_TOKENS_K = intPreferencesKey("auto_compaction_threshold_tokens_k")
        val AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS = intPreferencesKey("auto_compaction_keep_recent_tool_calls")
        val CONTEXT_COMPACTION_TARGET_TOKENS_K = intPreferencesKey("context_compaction_target_tokens_k")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val EXECUTION_BACKEND = stringPreferencesKey("execution_backend")
        val BACKEND_CONNECTIONS = stringPreferencesKey("backend_connections")
        // 会话累计用量（与压缩解耦；见 LifetimeUsage 注释）：{ 会话id -> LifetimeUsage }
        val CONV_LIFETIME_USAGE = stringPreferencesKey("conv_lifetime_usage")
        // 该键最多保留的会话数，避免无界增长
        const val LIFETIME_KEEP = 200
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val AUTO_GENERATE_TITLE = booleanPreferencesKey("auto_generate_title")
        val RESPONSE_STREAM_MAX_RETRIES = intPreferencesKey("response_stream_max_retries")
        val ENABLE_AUTO_RETRY = booleanPreferencesKey("enable_auto_retry")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_LOCAL_ENABLED = booleanPreferencesKey("ocr_local_enabled")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val AUTO_COMPRESS_ENABLED = booleanPreferencesKey("auto_compress_enabled")
        val AUTO_COMPRESS_THRESHOLD = intPreferencesKey("auto_compress_threshold")
        val AUTO_COMPRESS_TOKEN_LIMIT = longPreferencesKey("auto_compress_token_limit")
        val AUTO_COMPRESS_TOKEN_BASE = longPreferencesKey("auto_compress_token_base")
        val CONTEXT_WINDOW_SIZE = longPreferencesKey("context_window_size")
        val AUTO_COMPRESS_MODE = intPreferencesKey("auto_compress_mode")
        val TOOL_OUTPUT_ENABLED = booleanPreferencesKey("tool_output_enabled")
        val TOOL_OUTPUT_MAX_CHARS = intPreferencesKey("tool_output_max_chars")
        val TOOL_OUTPUT_COMPACT_MAX_CHARS = intPreferencesKey("tool_output_compact_max_chars")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")
        // IDs of built-in providers the user explicitly deleted; the re-seed pass
        // skips these so deletions are sticky across app restarts.
        val DELETED_BUILTIN_PROVIDER_IDS = stringPreferencesKey("deleted_builtin_provider_ids")
        // Names of bundled default-on skills already seeded once; the re-seed pass
        // skips these so a later user disable sticks across app restarts.
        val AUTO_ENABLED_DEFAULT_SKILLS = stringPreferencesKey("auto_enabled_default_skills")
        // 用户显式删除的内置技能名。删除会连带清掉技能目录里的哨兵文件，只有把这个记录
        // 放在目录之外，才能区分「从未安装」与「装过又被删」，避免重启后被重新种回来。
        val DELETED_BUNDLED_SKILLS = stringPreferencesKey("deleted_bundled_skills")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // 子代理
        val SUB_AGENTS = stringPreferencesKey("sub_agents")
        /** 子代理默认工作区（未指定时子代理跑在哪里；null = 跟随父助手）。 */
        val SUB_AGENT_DEFAULT_WORKSPACE = stringPreferencesKey("sub_agent_default_workspace")
        /** 前台派发的子代理完成时是否也在会话里留一条回执（默认关：前台结果已作为工具返回值）。 */
        val SUB_AGENT_FOREGROUND_RECEIPT = booleanPreferencesKey("sub_agent_foreground_receipt")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")
        val WEBDAV_CONFIGS = stringPreferencesKey("webdav_configs")
        val ACTIVE_WEBDAV_CONFIG_ID = stringPreferencesKey("active_webdav_config_id")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")
        val S3_CONFIGS = stringPreferencesKey("s3_configs")
        val ACTIVE_S3_CONFIG_ID = stringPreferencesKey("active_s3_config_id")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val LOCAL_MCP_SERVER_ENABLED = booleanPreferencesKey("local_mcp_server_enabled")
        val LOCAL_MCP_PROFILES = stringPreferencesKey("local_mcp_profiles")
        val LOCAL_MCP_ACTIVE_PROFILE_ID = stringPreferencesKey("local_mcp_active_profile_id")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_LISTEN_SCOPE = stringPreferencesKey("web_server_listen_scope")
        val WEB_SERVER_ALLOWED_NETWORKS = stringPreferencesKey("web_server_allowed_networks")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        /** 全局「网段预设」：Web 服务器 / 调试接口 / 本地 MCP 共用（CIDR 列表，JSON 数组） */
        val NETWORK_CIDR_PRESETS = stringPreferencesKey("network_cidr_presets")
        val SETTING_SHORTCUT_IDS = stringPreferencesKey("setting_shortcut_ids")
        val WORKSPACE_APK_MIRROR = stringPreferencesKey("workspace_apk_mirror")
        val WORKSPACE_APT_MIRROR = stringPreferencesKey("workspace_apt_mirror")
        val WORKSPACE_PIP_MIRROR = stringPreferencesKey("workspace_pip_mirror")
        val WORKSPACE_NPM_MIRROR = stringPreferencesKey("workspace_npm_mirror")
        val WORKSPACE_SDCARD_ENABLED = booleanPreferencesKey("workspace_sdcard_enabled")
        val WORKSPACE_CHROOT_ENABLED = booleanPreferencesKey("workspace_chroot_enabled")
        val WORKSPACE_SHELL_BACKEND = stringPreferencesKey("workspace_shell_backend")
        // 工作区资源画像缓存（JSON：id -> {rootBytes, packageCount, kernel, at}）。
        // 大工作区全树遍历可达分钟级，跨进程持久化让详情页秒显上次结果、后台静默刷新。
        val WORKSPACE_STATS_CACHE = stringPreferencesKey("workspace_stats_cache")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // Web 桥（全局配置，SSH 反向隧道到 ECS）
        val WEB_BRIDGE_ECS_HOST = stringPreferencesKey("web_bridge_ecs_host")
        val WEB_BRIDGE_ECS_USER = stringPreferencesKey("web_bridge_ecs_user")
        val WEB_BRIDGE_ECS_PORT = intPreferencesKey("web_bridge_ecs_port")
        val WEB_BRIDGE_REMOTE_PORT = intPreferencesKey("web_bridge_remote_port")
        val WEB_BRIDGE_LOCAL_PORT = intPreferencesKey("web_bridge_local_port")
        val WEB_BRIDGE_PRIVATE_KEY_PATH = stringPreferencesKey("web_bridge_private_key_path")
        val WEB_BRIDGE_PASSWORD = stringPreferencesKey("web_bridge_password")
        val WEB_BRIDGE_CREDENTIAL_REF = stringPreferencesKey("web_bridge_credential_ref")

        // AI logging
        val AI_LOG_LEVEL = stringPreferencesKey("ai_log_level")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")
        val BACKUP_ENCRYPTION_ENABLED = booleanPreferencesKey("backup_encryption_enabled")
        val BACKUP_ENCRYPTION_PASSWORD_ENC = stringPreferencesKey("backup_encryption_password_enc")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")
        internal suspend fun writeColdSettings(context: Context, settings: Settings, stagedFile: File) {
            require(!settings.init) { "Cannot stage uninitialized settings" }
            stagedFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
            val coldScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
            try {
                val cold = PreferenceDataStoreFactory.create(scope = coldScope, produceFile = { stagedFile })
                persistSettingsTo(cold, settings)
            } finally {
                coldScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

    }

    // Do not turn a failed disk read into emptyPreferences(): that makes defaults look
    // authoritative and a subsequent edit can overwrite the user's real settings.
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val retry = cause is IOException && attempt < SETTINGS_READ_RETRIES
            if (retry) {
                AppLog.w(TAG, "Settings read failed; retry ${attempt + 1}/$SETTINGS_READ_RETRIES", cause)
                delay(150L shl attempt.toInt())
            }
            retry
        }
        .catch { cause ->
            AppLog.e(TAG, "Settings read failed permanently; refusing to use defaults", cause)
            throw cause
        }
        .map { preferences ->
            if (settingsFlow.value.init && memoryRevision.get() == 0L) {
                val preview = extractFastUiPreview(preferences, settingsFlow.value)
                if (preview != settingsFlow.value) {
                    settingsFlow.value = preview
                }
            }
            Settings(
                favoriteModels = decodePrefOrDefault(preferences[FAVORITE_MODELS], emptyList()),
                chatModelId = parseUuidPrefOrNull(preferences[SELECT_MODEL])
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = parseUuidPrefOrNull(preferences[FAST_MODEL])
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = parseUuidPrefOrNull(preferences[TITLE_MODEL]),
                translateModeId = parseUuidPrefOrNull(preferences[TRANSLATE_MODEL])
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                autoGenerateTitle = preferences[AUTO_GENERATE_TITLE] != false,
                responseStreamMaxRetries = preferences[RESPONSE_STREAM_MAX_RETRIES] ?: 5,
                enableAutoRetry = preferences[ENABLE_AUTO_RETRY] != false,
                suggestionModelId = parseUuidPrefOrNull(preferences[SUGGESTION_MODEL]),
                imageGenerationModelId = parseUuidPrefOrNull(preferences[IMAGE_GENERATION_MODEL]) ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = parseUuidPrefOrNull(preferences[OCR_MODEL]) ?: Uuid.random(),
                ocrLocalEnabled = preferences[OCR_LOCAL_ENABLED] ?: true,
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = parseUuidPrefOrNull(preferences[COMPRESS_MODEL]) ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                autoCompressEnabled = preferences[AUTO_COMPRESS_ENABLED] ?: false,
                autoCompressThreshold = preferences[AUTO_COMPRESS_THRESHOLD] ?: 80,
                autoCompressTokenLimit = preferences[AUTO_COMPRESS_TOKEN_LIMIT] ?: 0L,
                autoCompressTokenBase = preferences[AUTO_COMPRESS_TOKEN_BASE] ?: 1_000L * 1000L,
                autoCompressMode = preferences[AUTO_COMPRESS_MODE] ?: 0,
                contextWindowSize = preferences[CONTEXT_WINDOW_SIZE] ?: 0L,
                toolOutputEnabled = preferences[TOOL_OUTPUT_ENABLED] ?: true,
                toolOutputMaxChars = preferences[TOOL_OUTPUT_MAX_CHARS] ?: 8 * 1000,
                toolOutputCompactMaxChars = preferences[TOOL_OUTPUT_COMPACT_MAX_CHARS] ?: 2 * 1000,
                assistantId = parseUuidPrefOrNull(preferences[SELECT_ASSISTANT])
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = decodePrefOrDefault(preferences[ASSISTANT_TAGS], emptyList()),
                mediaGenerationProviders = decodeEncryptedPrefOrDefault(preferences[MEDIA_GENERATION_PROVIDERS], emptyList()),
                providers = decodeProvidersFieldEncrypted(preferences[PROVIDERS]),
                deletedBuiltInProviderIds = preferences[DELETED_BUILTIN_PROVIDER_IDS]
                    ?.let { raw ->
                        JsonInstant.decodeFromString<Set<String>>(raw).map { Uuid.parse(it) }.toSet()
                    } ?: emptySet(),
                autoEnabledDefaultSkills = preferences[AUTO_ENABLED_DEFAULT_SKILLS]
                    ?.let { raw ->
                        JsonInstant.decodeFromString<Set<String>>(raw)
                    } ?: emptySet(),
                deletedBundledSkills = preferences[DELETED_BUNDLED_SKILLS]?.let { raw ->
                    JsonInstant.decodeFromString<Set<String>>(raw)
                } ?: emptySet(),
                assistants = decodePrefOrDefault(preferences[ASSISTANTS], emptyList()),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = decodePrefOrDefault(preferences[CUSTOM_THEMES], emptyList()),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = decodePrefOrDefault(preferences[DISPLAY_SETTING], DisplaySetting()),
                networkSetting = decodeEncryptedPrefOrDefault(preferences[NETWORK_SETTING], NetworkSetting()),
                executionBackend = preferences[EXECUTION_BACKEND] ?: "local",
                backendConnections = preferences[BACKEND_CONNECTIONS]?.let {
                    JsonInstant.decodeFromString<kotlin.collections.List<me.rerere.rikkahub.data.model.BackendConnection>>(it)
                } ?: emptyList(),
                // 显式类型实参：不能让 T 由 default 实参推。DEFAULT 一旦声明成具体子类
                // （DuckDuckGoOptions），T 就窄化成 List<DuckDuckGoOptions>，多态判别键 `type`
                // 被 ignoreUnknownKeys 吞掉 → 所有搜索服务静默解成 DuckDuckGo。
                // SearchServiceOptions.DEFAULT 已修声明类型，这里再钉一次作双保险。
                searchServices = decodeEncryptedPrefOrDefault<List<SearchServiceOptions>>(
                    preferences[SEARCH_SERVICES],
                    listOf(SearchServiceOptions.DEFAULT),
                ).also {
                    logSearchServicesDecode(
                        preferences[SEARCH_SERVICES]?.let(::decryptPrefValue),
                        it,
                    )
                }, // TEMP-DIAG(SearchTypeDiag)：验收后删掉这个 .also{} 片段
                searchCommonOptions = decodePrefOrDefault(preferences[SEARCH_COMMON], SearchCommonOptions()),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = decodeEncryptedPrefOrDefault(preferences[MCP_SERVERS], emptyList()),
subAgents = preferences[SUB_AGENTS]?.let { raw ->
                    JsonInstant.decodeFromString<List<SubAgentProfile>>(raw)
                } ?: emptyList(),
                subAgentDefaultWorkspaceId = preferences[SUB_AGENT_DEFAULT_WORKSPACE]?.let { raw ->
                    Uuid.parse(raw)
                },
                subAgentForegroundReceipt = preferences[SUB_AGENT_FOREGROUND_RECEIPT] ?: false,
                webDavConfig = decodeEncryptedPrefOrDefault(preferences[WEBDAV_CONFIG], WebDavConfig()),
                s3Config = decodeEncryptedPrefOrDefault(preferences[S3_CONFIG], S3Config()),
                uploadS3Config = decodeEncryptedPrefOrDefault(preferences[UPLOAD_S3_CONFIG], S3Config()),
                webDavConfigs = preferences[WEBDAV_CONFIGS]?.let { raw ->
                    JsonInstant.decodeFromString<List<WebDavConfig>>(decryptPrefValue(raw))
                } ?: emptyList(),
                activeWebDavConfigId = preferences[ACTIVE_WEBDAV_CONFIG_ID],
                s3Configs = preferences[S3_CONFIGS]?.let { raw ->
                    JsonInstant.decodeFromString<List<S3Config>>(decryptPrefValue(raw))
                } ?: emptyList(),
                activeS3ConfigId = preferences[ACTIVE_S3_CONFIG_ID],
                ttsProviders = decodeEncryptedPrefOrDefault(preferences[TTS_PROVIDERS], emptyList()),
                selectedTTSProviderId = parseUuidPrefOrNull(preferences[SELECTED_TTS_PROVIDER])
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = decodeEncryptedPrefOrDefault(preferences[ASR_PROVIDERS], emptyList()),
                selectedASRProviderId = parseUuidPrefOrNull(preferences[SELECTED_ASR_PROVIDER]),
                modeInjections = decodePrefOrDefault(preferences[MODE_INJECTIONS], emptyList()),
                lorebooks = decodePrefOrDefault(preferences[LOREBOOKS], emptyList()),
                quickMessages = decodePrefOrDefault(preferences[QUICK_MESSAGES], emptyList()),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                localMcpServerEnabled = preferences[LOCAL_MCP_SERVER_ENABLED] == true,
                localMcpProfiles = preferences[LOCAL_MCP_PROFILES]?.let {
                    JsonInstant.decodeFromString<List<LocalMcpProfile>>(it)
                } ?: emptyList(),
                activeLocalMcpProfileId = preferences[LOCAL_MCP_ACTIVE_PROFILE_ID],
                webBridgeEcsHost = preferences[WEB_BRIDGE_ECS_HOST] ?: "",
                webBridgeEcsUser = preferences[WEB_BRIDGE_ECS_USER] ?: "root",
                webBridgeEcsPort = preferences[WEB_BRIDGE_ECS_PORT] ?: 22,
                webBridgeRemotePort = preferences[WEB_BRIDGE_REMOTE_PORT] ?: 8080,
                webBridgeLocalPort = preferences[WEB_BRIDGE_LOCAL_PORT] ?: 8080,
                webBridgePrivateKeyPath = preferences[WEB_BRIDGE_PRIVATE_KEY_PATH] ?: "",
                webBridgePassword = preferences[WEB_BRIDGE_PASSWORD]?.let(::decryptPrefValue) ?: "",
                webBridgeCredentialRef = preferences[WEB_BRIDGE_CREDENTIAL_REF] ?: "",
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerListenScope = preferences[WEB_SERVER_LISTEN_SCOPE] ?: "loopback",
                webServerAllowedNetworks = preferences[WEB_SERVER_ALLOWED_NETWORKS] ?: "",
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD]?.let(::decryptPrefValue) ?: "",
                networkCidrPresets =
                    preferences[NETWORK_CIDR_PRESETS]?.let {
                        JsonInstant.decodeFromString<List<String>>(it)
                    } ?: emptyList(),
                settingShortcutIds =
                    preferences[SETTING_SHORTCUT_IDS]
                        ?.split('\u0001')
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                workspaceApkMirror = preferences[WORKSPACE_APK_MIRROR] ?: "",
                workspaceAptMirror = preferences[WORKSPACE_APT_MIRROR] ?: "",
                workspacePipMirror = preferences[WORKSPACE_PIP_MIRROR] ?: "",
                workspaceNpmMirror = preferences[WORKSPACE_NPM_MIRROR] ?: "",
                workspaceSdcardEnabled = preferences[WORKSPACE_SDCARD_ENABLED] ?: false,
                workspaceChrootEnabled =
                    WorkspaceShellBackend.fromPreference(
                        rawBackend = preferences[WORKSPACE_SHELL_BACKEND],
                        legacyChrootEnabled = preferences[WORKSPACE_CHROOT_ENABLED],
                    ) == WorkspaceShellBackend.ROOT_CHROOT,
                workspaceShellBackend =
                    WorkspaceShellBackend.fromPreference(
                        rawBackend = preferences[WORKSPACE_SHELL_BACKEND],
                        legacyChrootEnabled = preferences[WORKSPACE_CHROOT_ENABLED],
                    ),
                workspaceStatsCache = preferences[WORKSPACE_STATS_CACHE] ?: "{}",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                aiLogLevel = AiLogLevel.fromPreference(preferences[AI_LOG_LEVEL]),
                backupReminderConfig = decodePrefOrDefault(preferences[BACKUP_REMINDER_CONFIG], BackupReminderConfig()),
                backupEncryptionEnabled = preferences[BACKUP_ENCRYPTION_ENABLED] ?: false,
                backupEncryptionPasswordEnc = preferences[BACKUP_ENCRYPTION_PASSWORD_ENC] ?: "",
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
            )
        }
        .map {
            val deletedDefaultIds = it.deletedBuiltInProviderIds
            var providers = it.providers.ifEmpty {
                DEFAULT_PROVIDERS.filter { p -> p.id !in deletedDefaultIds }
            }.toMutableList()
            // For existing installs that pre-date the on-device AICore provider being
            // promoted to first-place, hoist it to the top so the user does not have to
            // scroll past every legacy aggregator to find it.
            val aicoreIndex = providers.indexOfFirst { it is ProviderSetting.AICore }
            if (aicoreIndex > 0) {
                val aicoreRow = providers.removeAt(aicoreIndex)
                providers.add(0, aicoreRow)
            }
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (defaultProvider.id in deletedDefaultIds) return@forEach
                if (providers.none { it.id == defaultProvider.id }) {
                    // On-device built-in providers (AICore, LiteRT) are pinned to the top of
                    // the list in the order they appear in DEFAULT_PROVIDERS. Remote provider
                    // defaults continue to append at the end so existing users see no
                    // reordering of their configured remote providers.
                    when (defaultProvider) {
                        is ProviderSetting.AICore -> providers.add(0, defaultProvider.copyProvider())
                        is ProviderSetting.LiteRtLocal -> {
                            // Insert right after AICore, or at 0 if AICore is absent.
                            // indexOfFirst returns -1 when absent; -1 + 1 = 0, so insert at 0.
                            val insertAt = providers.indexOfFirst { it is ProviderSetting.AICore } + 1
                            providers.add(insertAt, defaultProvider.copyProvider())
                        }
                        else -> providers.add(defaultProvider.copyProvider())
                    }
                }
            }
            providers = providers.map { provider ->
                // 注册表（ProviderDescriptors）是描述文案的唯一来源，按 id 索引；
                // 这里只同步 builtIn 标记，不再拷贝 description（ai 模块已去 Compose 化）
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(builtIn = defaultProvider.builtIn)
                } else provider
            }.toMutableList()
            var assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            // Skills are never auto-enabled: Assistant.enabledSkills is authoritative. What the
            // user toggles on/off — or deletes — in Settings is exactly what the runtime sees.
            // No re-pinning of emptied sets, no additive seeding of bundled skills on upgrade.
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.AICore -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LiteRtLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Codex -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Grok -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Backend -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.GeminiOAuth -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.LlamaCppLocal -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .distinctUntilChanged()
        .onEach {
            if (hasCompletedInitialDecode) {
                get<PebbleEngine>().templateCache.invalidateAll()
            } else {
                hasCompletedInitialDecode = true
            }
        }
        // Preference decoding includes provider/model JSON and must not run on Main.
        .flowOn(Dispatchers.IO)

    private val transformLock = kotlinx.coroutines.sync.Mutex()
    private val memoryRevision = AtomicLong(0)
    private val initialization = CompletableDeferred<Unit>()

    // A disk emission queued before a write may be stale. Revision-check it after
    // taking the same mutex used by updates, so it cannot overwrite newer memory.
    val settingsFlow = MutableStateFlow(loadWarmUiSettings(appContext))

    init {
        scope.launch {
            try {
                settingsFlowRaw.distinctUntilChanged().collect { decoded ->
                    val observedRevision = memoryRevision.get()
                    transformLock.withLock {
                        if (settingsFlow.value.init || observedRevision == memoryRevision.get()) {
                            if (settingsFlow.value.init || settingsFlow.value != decoded) {
                                settingsFlow.value = decoded
                            }
                            saveWarmUiSettings(appContext, decoded)
                            me.rerere.rikkahub.data.network.AppDnsResolver.updateNetworkSetting(decoded.networkSetting)
                        }
                        if (!settingsFlow.value.init) initialization.complete(Unit)
                    }
                }
            } catch (cancelled: CancellationException) {
                initialization.completeExceptionally(cancelled)
                AppLog.e(TAG, "Settings initialization cancelled", cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                initialization.completeExceptionally(failure)
                AppLog.e(TAG, "Settings initialization failed; stored values were not replaced", failure)
            }
        }.invokeOnCompletion { cause ->
            if (!initialization.isCompleted) {
                initialization.completeExceptionally(cause ?: IllegalStateException("Settings collector stopped before initialization"))
            }
        }
    }

    /** Wait for the first valid disk decode; propagates permanent read/decode failures. */
    suspend fun awaitInitialized() = initialization.await()

    /**
     * 等待设置首次可用，但**绝不无限挂起**：初始化永久失败（密钥/解码错误）或超时都返回 null，
     * 由调用方给出可见反馈。用于“等不到设置就会静默失效”的 UI/服务路径。
     */
    suspend fun awaitReady(timeoutMs: Long = SETTINGS_READY_TIMEOUT_MS): Settings? =
        withTimeoutOrNull(timeoutMs) {
            try {
                awaitInitialized()
                settingsFlow.value.takeIf { !it.init }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AppLog.w(TAG, "Settings unavailable for consumer", failure)
                null
            }
        }

    /** Explicit whole-settings replacement for backup/import restore flows only. */
    suspend fun replaceSettings(settings: Settings) {
        initialization.await()
        transformLock.withLock { persistSettings(settings, forceFullWrite = true) }
    }

    @Suppress("LongMethod") // 逐字段平铺落盘，拆分会把同一份键清单割裂成多段（与仓内既有写法口径一致）
    private suspend fun persistSettings(settings: Settings, forceFullWrite: Boolean = false) {
        if (settings.init) error("Cannot persist dummy Settings")
        val previous = settingsFlow.value
        memoryRevision.incrementAndGet()
        settingsFlow.value = settings
        saveWarmUiSettings(appContext, settings)
        me.rerere.rikkahub.data.network.AppDnsResolver.updateNetworkSetting(settings.networkSetting)
        try {
            val diffBase = if (forceFullWrite) null else previous.takeIf { !it.init }
            persistSettingsTo(dataStore, settings, diffBase)
        } catch (failure: Throwable) {
            settingsFlow.value = previous
            saveWarmUiSettings(appContext, previous)
            me.rerere.rikkahub.data.network.AppDnsResolver.updateNetworkSetting(previous.networkSetting)
            memoryRevision.incrementAndGet()
            throw failure
        }
    }

    // ---------- 会话累计用量（与压缩解耦，见 LifetimeUsage 注释） ----------

    @Volatile
    private var cachedLifetimeUsageRaw: String? = null

    @Volatile
    private var cachedLifetimeUsageMap: Map<String, LifetimeUsage> = emptyMap()

    private fun decodeConvLifetimeUsageMap(raw: String?): Map<String, LifetimeUsage> {
        if (raw.isNullOrBlank()) return emptyMap()
        if (raw == cachedLifetimeUsageRaw) return cachedLifetimeUsageMap
        val decoded =
            runCatching { JsonInstant.decodeFromString<Map<String, LifetimeUsage>>(raw) }
                .getOrDefault(emptyMap())
        cachedLifetimeUsageRaw = raw
        cachedLifetimeUsageMap = decoded
        return decoded
    }

    /** 订阅某会话的累计用量（仅当 CONV_LIFETIME_USAGE 键变化时在后台解码，避免流式生成每帧重复反序列化）。 */
    fun convLifetimeUsageFlow(conversationId: String): kotlinx.coroutines.flow.Flow<LifetimeUsage?> =
        dataStore.data
            .map { it[CONV_LIFETIME_USAGE] }
            .distinctUntilChanged()
            .map { raw -> decodeConvLifetimeUsageMap(raw)[conversationId] }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** 读取某会话的累计用量；无记录返回 null，调用方回退到「当前聚合」显示。 */
    suspend fun getConvLifetimeUsage(conversationId: String): LifetimeUsage? =
        runCatching {
            val raw = dataStore.data.first()[CONV_LIFETIME_USAGE] ?: return null
            decodeConvLifetimeUsageMap(raw)[conversationId]
        }.getOrNull()

    /**
     * 累加**一次请求**的用量（事件驱动：每次 API 请求各计一次，口径同平台账单）。
     *
     * 调用点在每次请求收到 usage 时（见 `GenerationChunk.UsageIncurred` 及其消费处）—— 不走
     * 「事后扫消息」，因为一轮里的多步工具调用共用同一条消息、usage 被覆盖写入，那样会少算数倍。
     */
    suspend fun accumulateConvUsage(conversationId: String, usage: TokenUsage) {
        val prompt = usage.promptTokens.coerceAtLeast(0)
        val snapshot =
            MessageUsageSnapshot(
                input = prompt.toLong(),
                cached = usage.cachedTokens.coerceIn(0, prompt).toLong(),
                output = usage.completionTokens.coerceAtLeast(0).toLong(),
                cost = (usage.cost ?: 0.0).coerceAtLeast(0.0),
            )
        dataStore.edit { preferences ->
            val current =
                preferences[CONV_LIFETIME_USAGE]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, LifetimeUsage>>(it) }.getOrNull()
                } ?: emptyMap()
            val prev = current[conversationId] ?: LifetimeUsage()
            val next = accumulateLifetimeUsage(prev, snapshot)
            val updated = LinkedHashMap(current).apply {
                remove(conversationId)
                put(conversationId, next)
            }
            val trimmed =
                if (updated.size <= LIFETIME_KEEP) {
                    updated
                } else {
                    LinkedHashMap(updated.entries.toList().takeLast(LIFETIME_KEEP).associate { it.key to it.value })
                }
            preferences[CONV_LIFETIME_USAGE] = JsonInstant.encodeToString<Map<String, LifetimeUsage>>(trimmed)
        }
    }

    /**
     * 确保某会话的累计用量至少不低于 [baseline]（用于 `/compact` 压缩删减历史节点前，
     * 将存量消息的累计值固化到持久化账本中，避免历史/导入会话压缩后累计归零或变小）。
     */
    suspend fun ensureConvLifetimeBaseline(conversationId: String, baseline: LifetimeUsage) {
        if (baseline.inputTokens <= 0L && baseline.outputTokens <= 0L && baseline.turns <= 0) return
        dataStore.edit { preferences ->
            val current =
                preferences[CONV_LIFETIME_USAGE]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, LifetimeUsage>>(it) }.getOrNull()
                } ?: emptyMap()
            val prev = current[conversationId] ?: LifetimeUsage()
            val mergedInput = maxOf(prev.inputTokens, baseline.inputTokens)
            val merged =
                LifetimeUsage(
                    inputTokens = mergedInput,
                    cachedTokens = maxOf(prev.cachedTokens, baseline.cachedTokens).coerceAtMost(mergedInput),
                    outputTokens = maxOf(prev.outputTokens, baseline.outputTokens),
                    turns = maxOf(prev.turns, baseline.turns),
                    costUsd = maxOf(prev.costUsd, baseline.costUsd),
                )
            if (merged == prev && conversationId in current) return@edit
            val updated = LinkedHashMap(current).apply {
                remove(conversationId)
                put(conversationId, merged)
            }
            val trimmed =
                if (updated.size <= LIFETIME_KEEP) {
                    updated
                } else {
                    LinkedHashMap(updated.entries.toList().takeLast(LIFETIME_KEEP).associate { it.key to it.value })
                }
            preferences[CONV_LIFETIME_USAGE] = JsonInstant.encodeToString<Map<String, LifetimeUsage>>(trimmed)
        }
    }

    /** 写入某会话的累计用量；只保留最近 [LIFETIME_KEEP] 个会话，避免无界增长。 */
    suspend fun setConvLifetimeUsage(conversationId: String, usage: LifetimeUsage) {
        dataStore.edit { preferences ->
            val current =
                preferences[CONV_LIFETIME_USAGE]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, LifetimeUsage>>(it) }.getOrNull()
                } ?: emptyMap()
            val updated = LinkedHashMap(current).apply {
                remove(conversationId)
                put(conversationId, usage)
            }
            val trimmed =
                if (updated.size <= LIFETIME_KEEP) {
                    updated
                } else {
                    LinkedHashMap(updated.entries.toList().takeLast(LIFETIME_KEEP).associate { it.key to it.value })
                }
            preferences[CONV_LIFETIME_USAGE] = JsonInstant.encodeToString<Map<String, LifetimeUsage>>(trimmed)
        }
    }

    /** Serialises rapid-fire transform-based updates so concurrent callers don't race
     *  each other. Without this lock, two `update {fn}` calls dispatched in quick
     *  succession both snapshot `settingsFlow.value` BEFORE either has written, then
     *  each writes its own delta off the same stale base — last writer wins, the
     *  earlier change is silently dropped. The most-visible repro: rapid-fire taps on
     *  per-assistant tool toggles where every other tap appeared to revert. */
    suspend fun update(fn: (Settings) -> Settings) {
        initialization.await()
        transformLock.withLock {
            persistSettings(fn(settingsFlow.value))
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        update { current -> current.copy(assistantId = assistantId) }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}
