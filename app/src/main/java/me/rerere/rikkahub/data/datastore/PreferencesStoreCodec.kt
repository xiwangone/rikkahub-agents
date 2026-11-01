package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchServiceOptions
import org.koin.core.component.get
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.log.AppLog

import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ACTIVE_S3_CONFIG_ID
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ACTIVE_WEBDAV_CONFIG_ID
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AI_LOG_LEVEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ASR_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ASSISTANTS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ASSISTANT_TAGS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_COMPRESS_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_COMPRESS_MODE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_COMPRESS_THRESHOLD
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_COMPRESS_TOKEN_BASE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_COMPRESS_TOKEN_LIMIT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_ENABLED_DEFAULT_SKILLS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.AUTO_GENERATE_TITLE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.BACKEND_CONNECTIONS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.BACKUP_ENCRYPTION_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.BACKUP_ENCRYPTION_PASSWORD_ENC
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.BACKUP_REMINDER_CONFIG
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.COMPRESS_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.COMPRESS_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.CONTEXT_WINDOW_SIZE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.CUSTOM_THEMES
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DEFAULT_TTS_PLAYBACK_SPEED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DELETED_BUILTIN_PROVIDER_IDS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DELETED_BUNDLED_SKILLS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DEVELOPER_MODE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DISPLAY_SETTING
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.DYNAMIC_COLOR
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ENABLE_AUTO_RETRY
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.ENABLE_SUGGESTION
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.EXECUTION_BACKEND
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.FAST_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.FAVORITE_MODELS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.IMAGE_GENERATION_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.LAUNCH_COUNT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.LOCAL_MCP_ACTIVE_PROFILE_ID
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.LOCAL_MCP_PROFILES
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.LOCAL_MCP_SERVER_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.LOREBOOKS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.MCP_SERVERS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.MEDIA_GENERATION_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.MODE_INJECTIONS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.NETWORK_CIDR_PRESETS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.NETWORK_SETTING
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.OCR_LOCAL_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.OCR_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.OCR_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.QUICK_MESSAGES
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.RESPONSE_STREAM_MAX_RETRIES
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.S3_CONFIG
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.S3_CONFIGS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SEARCH_COMMON
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SEARCH_SELECTED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SEARCH_SERVICES
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SELECTED_ASR_PROVIDER
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SELECTED_TTS_PROVIDER
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SELECT_ASSISTANT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SELECT_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SETTING_SHORTCUT_IDS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SPONSOR_ALERT_DISMISSED_AT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SUB_AGENTS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SUB_AGENT_DEFAULT_WORKSPACE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SUB_AGENT_FOREGROUND_RECEIPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SUGGESTION_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.SUGGESTION_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.THEME_ID
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TITLE_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TITLE_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TOOL_OUTPUT_COMPACT_MAX_CHARS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TOOL_OUTPUT_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TOOL_OUTPUT_MAX_CHARS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TRANSLATE_MODEL
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TRANSLATE_THINKING_BUDGET
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.TTS_PROVIDERS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.UPLOAD_S3_CONFIG
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEBDAV_CONFIG
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEBDAV_CONFIGS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_CREDENTIAL_REF
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_ECS_HOST
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_ECS_PORT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_ECS_USER
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_LOCAL_PORT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_PASSWORD
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_PRIVATE_KEY_PATH
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_BRIDGE_REMOTE_PORT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_ACCESS_PASSWORD
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_ALLOWED_NETWORKS
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_JWT_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_LISTEN_SCOPE
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_LOCALHOST_ONLY
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WEB_SERVER_PORT
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_APK_MIRROR
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_APT_MIRROR
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_CHROOT_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_NPM_MIRROR
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_PIP_MIRROR
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_SDCARD_ENABLED
import me.rerere.rikkahub.data.datastore.SettingsStore.Companion.WORKSPACE_STATS_CACHE

/** Strictly decode stored providers: one invalid entry must fail initialization, never erase the rest on the next write. */
internal fun decodeProvidersTolerant(raw: String): List<ProviderSetting> {
    if (raw.isBlank()) return emptyList()
    val array = JsonInstant.parseToJsonElement(raw) as? JsonArray
        ?: error("Persisted providers setting is not a JSON array")
    return array.map { element -> JsonInstant.decodeFromJsonElement<ProviderSetting>(element) }
}


        internal suspend fun persistSettingsTo(dataStore: DataStore<Preferences>, settings: Settings) {
            dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[NETWORK_SETTING] = encryptPrefValue(JsonInstant.encodeToString(settings.networkSetting))

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            preferences[AUTO_GENERATE_TITLE] = settings.autoGenerateTitle
            preferences[RESPONSE_STREAM_MAX_RETRIES] = settings.responseStreamMaxRetries.coerceIn(0, 10)
            preferences[ENABLE_AUTO_RETRY] = settings.enableAutoRetry
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_LOCAL_ENABLED] = settings.ocrLocalEnabled
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt
            preferences[AUTO_COMPRESS_ENABLED] = settings.autoCompressEnabled
            preferences[AUTO_COMPRESS_THRESHOLD] = settings.autoCompressThreshold
            preferences[AUTO_COMPRESS_TOKEN_LIMIT] = settings.autoCompressTokenLimit
            preferences[AUTO_COMPRESS_TOKEN_BASE] = settings.autoCompressTokenBase
            preferences[AUTO_COMPRESS_MODE] = settings.autoCompressMode
            preferences[CONTEXT_WINDOW_SIZE] = settings.contextWindowSize
            preferences[TOOL_OUTPUT_ENABLED] = settings.toolOutputEnabled
            preferences.putToolOutputLimits(settings)

            // P0 凭证加密：providers 含 apiKey / 服务账号私钥等凭证，以 AES-GCM 密文入库，
            // 明文 JSON 不再落盘（密钥在 AndroidKeyStore，随应用卸载清除）。
            preferences[MEDIA_GENERATION_PROVIDERS] = encryptPrefValue(JsonInstant.encodeToString(settings.mediaGenerationProviders))
            preferences[PROVIDERS] = ProviderCredentialCipher.encrypt(
                JsonInstant.encodeToString(settings.providers)
            )
            preferences[DELETED_BUILTIN_PROVIDER_IDS] = JsonInstant.encodeToString(
                settings.deletedBuiltInProviderIds.map { it.toString() }.toSet()
            )
            preferences[AUTO_ENABLED_DEFAULT_SKILLS] = JsonInstant.encodeToString(
                settings.autoEnabledDefaultSkills
            )
            preferences[DELETED_BUNDLED_SKILLS] = JsonInstant.encodeToString(settings.deletedBundledSkills)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = encryptPrefValue(JsonInstant.encodeToString(settings.searchServices))
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            // maxOf(0, size - 1) guards the empty-list case: a persisted "[]" for
            // search_services leaves searchServices empty (the ?: default only fires on a
            // missing key, not on an empty array), and coerceIn(0, -1) throws
            // IllegalArgumentException because min > max — crashing every settings write.
            preferences[SEARCH_SELECTED] =
                settings.searchServiceSelected.coerceIn(0, maxOf(0, settings.searchServices.size - 1))

            preferences[MCP_SERVERS] = encryptPrefValue(JsonInstant.encodeToString(settings.mcpServers))
            preferences[SUB_AGENTS] = JsonInstant.encodeToString(settings.subAgents)
            putSubAgentDefaultWorkspace(preferences, settings)
            putSubAgentForegroundReceipt(preferences, settings)
            preferences[WEBDAV_CONFIG] = encryptPrefValue(JsonInstant.encodeToString(settings.webDavConfig))
            preferences[S3_CONFIG] = encryptPrefValue(JsonInstant.encodeToString(settings.s3Config))
            preferences[UPLOAD_S3_CONFIG] = encryptPrefValue(JsonInstant.encodeToString(settings.uploadS3Config))
            preferences[WEBDAV_CONFIGS] = encryptPrefValue(JsonInstant.encodeToString(settings.webDavConfigs))
            settings.activeWebDavConfigId?.let {
                preferences[ACTIVE_WEBDAV_CONFIG_ID] = it
            } ?: preferences.remove(ACTIVE_WEBDAV_CONFIG_ID)
            preferences[S3_CONFIGS] = encryptPrefValue(JsonInstant.encodeToString(settings.s3Configs))
            settings.activeS3ConfigId?.let {
                preferences[ACTIVE_S3_CONFIG_ID] = it
            } ?: preferences.remove(ACTIVE_S3_CONFIG_ID)
            preferences[TTS_PROVIDERS] = encryptPrefValue(JsonInstant.encodeToString(settings.ttsProviders))
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = encryptPrefValue(JsonInstant.encodeToString(settings.asrProviders))
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[LOCAL_MCP_SERVER_ENABLED] = settings.localMcpServerEnabled
            preferences[LOCAL_MCP_PROFILES] = JsonInstant.encodeToString(settings.localMcpProfiles)
            settings.activeLocalMcpProfileId?.let { preferences[LOCAL_MCP_ACTIVE_PROFILE_ID] = it }
            preferences[WEB_BRIDGE_ECS_HOST] = settings.webBridgeEcsHost
            preferences[WEB_BRIDGE_ECS_USER] = settings.webBridgeEcsUser
            preferences[WEB_BRIDGE_ECS_PORT] = settings.webBridgeEcsPort
            preferences[WEB_BRIDGE_REMOTE_PORT] = settings.webBridgeRemotePort
            preferences[WEB_BRIDGE_LOCAL_PORT] = settings.webBridgeLocalPort
            preferences[WEB_BRIDGE_PRIVATE_KEY_PATH] = settings.webBridgePrivateKeyPath
            preferences[WEB_BRIDGE_PASSWORD] = encryptPrefValue(settings.webBridgePassword)
            preferences[WEB_BRIDGE_CREDENTIAL_REF] = settings.webBridgeCredentialRef
            preferences.putWebServerBlock(settings)
            preferences[SETTING_SHORTCUT_IDS] = settings.settingShortcutIds.joinToString("\u0001")
            preferences.putWorkspaceMirrors(settings)
            preferences[WORKSPACE_SDCARD_ENABLED] = settings.workspaceSdcardEnabled
            preferences[WORKSPACE_CHROOT_ENABLED] = settings.workspaceChrootEnabled
            preferences[WORKSPACE_STATS_CACHE] = settings.workspaceStatsCache
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[AI_LOG_LEVEL] = settings.aiLogLevel.preferenceName
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[BACKUP_ENCRYPTION_ENABLED] = settings.backupEncryptionEnabled
            preferences[BACKUP_ENCRYPTION_PASSWORD_ENC] = settings.backupEncryptionPasswordEnc
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            preferences[EXECUTION_BACKEND] = settings.executionBackend
            preferences[BACKEND_CONNECTIONS] = JsonInstant.encodeToString(settings.backendConnections)
            }
        }

        internal fun putSubAgentForegroundReceipt(preferences: MutablePreferences, settings: Settings) {
            preferences[SUB_AGENT_FOREGROUND_RECEIPT] = settings.subAgentForegroundReceipt
        }

        internal fun putSubAgentDefaultWorkspace(preferences: MutablePreferences, settings: Settings) {
            val id = settings.subAgentDefaultWorkspaceId
            if (id == null) preferences.remove(SUB_AGENT_DEFAULT_WORKSPACE)
            else preferences[SUB_AGENT_DEFAULT_WORKSPACE] = id.toString()
        }

        internal fun encryptPrefValue(raw: String): String = ProviderCredentialCipher.encrypt(raw)

        /**
         * web 服务与「网段预设」一组写入。抽成扩展只为收敛 [update] 的体积（detekt LongMethod 阈值
         * 贴线），语义与内联写法一致。
         */
        internal fun MutablePreferences.putWebServerBlock(settings: Settings) {
            this[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            this[WEB_SERVER_LISTEN_SCOPE] = settings.webServerListenScope
            this[WEB_SERVER_ALLOWED_NETWORKS] = settings.webServerAllowedNetworks
            this[WEB_SERVER_ACCESS_PASSWORD] = encryptPrefValue(settings.webServerAccessPassword)
            this[NETWORK_CIDR_PRESETS] = JsonInstant.encodeToString(settings.networkCidrPresets)
        }

        internal fun MutablePreferences.putToolOutputLimits(settings: Settings) {
            this[TOOL_OUTPUT_MAX_CHARS] = settings.toolOutputMaxChars
            this[TOOL_OUTPUT_COMPACT_MAX_CHARS] = settings.toolOutputCompactMaxChars
        }

        /**
         * 写回沙箱包管理器镜像（apk/apt/pip/npm）。
         *
         * 抽成独立扩展函数同 [putToolOutputLimits]：让 [update] 保持在 detekt LongMethod 阈值内。
         */
        internal fun MutablePreferences.putWorkspaceMirrors(settings: Settings) {
            this[WORKSPACE_APK_MIRROR] = settings.workspaceApkMirror
            this[WORKSPACE_APT_MIRROR] = settings.workspaceAptMirror
            this[WORKSPACE_PIP_MIRROR] = settings.workspacePipMirror
            this[WORKSPACE_NPM_MIRROR] = settings.workspaceNpmMirror
        }


/**
 * 敏感 JSON 值落盘加解密（与 `providers` 同一条 Keystore 通道）。
 *
 * 覆盖：MCP 服务器配置（OAuth token / clientSecret）、WebDAV 与 S3 凭据、TTS / ASR
 * 的 provider 配置。历史明文（非密文格式）解密失败即回退原文，升级无需迁移。
 */
internal fun decryptPrefValue(raw: String): String = ProviderCredentialCipher.decrypt(raw) ?: raw

/** Decrypt and decode a stored field; malformed present values deliberately propagate. */
internal inline fun <reified T> decodeEncryptedPrefOrDefault(raw: String?, default: T): T {
    if (raw == null) return default
    return decodePersistedSetting(decryptPrefValue(raw), default)
}

/**
 * Missing keys use schema defaults; present-but-invalid values fail loading and are never rewritten.
 *
 * ⚠ `default` 同时是 **泛型 T 的类型见证**：Kotlin 取最具体解、不会按赋值目标放宽。所以
 * 对「声明为 sealed 基类的列表」不能传具体子类实例当 default ——
 * `decodePrefOrDefault<List<X>>(raw, listOf(XxxSubclass()))` 会把 T 窄化成
 * `List<XxxSubclass>`，多态判别键 `type` 被 `ignoreUnknownKeys` 静默忽略，**所有条目都解成那个
 * 子类**（写入侧仍正确，读写不对称掩盖问题）。这类调用一律**显式写类型实参**，并保证默认值
 * 的声明类型是基类（见 `SearchServiceOptions.DEFAULT`）。
 */
internal inline fun <reified T> decodePrefOrDefault(raw: String?, default: T): T {
    if (raw == null) return default
    return decodePersistedSetting(raw, default)
}

// TEMP-DIAG(SearchTypeDiag) —— 出包核对「盘上 type」与「读回实例类」是否一致用；验收后连同
// 上面 searchServices 里的那行 .also{} 一起删掉（全仓搜一次 SearchTypeDiag 即可清干净）。
/**
 * 打一条「解码保真」自检：JSON 里出现的 `type` 判别值 vs 读回实例的 typeKey/displayName。
 * 两者不一致（或实例类与 type 不匹配）即说明又有人把多态列表的泛型窄化成了具体子类。
 */
internal fun logSearchServicesDecode(raw: String?, services: List<SearchServiceOptions>) {
    if (raw.isNullOrBlank()) return
    val diskTypes = runCatching {
        (JsonInstant.parseToJsonElement(raw) as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("type") as? JsonPrimitive }
            ?.map { it.content }
    }.getOrDefault(emptyList())
    val memoryTypes = services.map { it.typeKey }
    AppLog.i(
        "SearchTypeDiag",
        "disk=$diskTypes memory=$memoryTypes classes=${services.map { it.javaClass.simpleName }} " +
            "display=${services.map { it.displayName }} fidelity=${diskTypes == memoryTypes}",
    )
}

/** Malformed stored UUID values fail loading instead of masquerading as absent optional fields. */
internal fun parseUuidPrefOrNull(raw: String?): Uuid? {
    if (raw.isNullOrBlank()) return null
    return Uuid.parse(raw)
}

