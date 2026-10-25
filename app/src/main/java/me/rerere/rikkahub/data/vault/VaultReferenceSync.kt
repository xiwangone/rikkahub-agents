package me.rerere.rikkahub.data.vault

import kotlinx.coroutines.flow.first
import me.rerere.asr.ASRProviderSetting
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.telegram.TelegramBotConfig
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting

/**
 * 引用同步：把配置里对某个凭证名的引用，整体改为另一个名字。
 *
 * 为什么必须有：改名如果只改库内，配置里按名字引用的地方会**静默失效**
 * （表现为莫名的 401 或连接失败）。[VaultReferenceLocator] 负责"查"，本类负责"改"，
 * 两者配套才谈得上"安全改名"。
 *
 * 只改**引用文本**，不触碰任何凭证值；改动位置数会返回给调用方用于报告与审计。
 */
object VaultReferenceSync {

    /** 引用前缀（与运行时解析层一致）。 */
    private const val PREFIX = "\$\$"

    /**
     * 把一段文本里的 `$$oldName` 精确替换为 `$$newName`。
     *
     * 用负向前瞻限定 token 边界，避免把 `$$OLD_SUFFIX` 这类更长名字误改。
     */
    fun renameInText(text: String, oldName: String, newName: String): String {
        // 空的新名会把 `$$OLD` 替换成 `$$`（无效引用，反而破坏配置）→ 防御性拒绝，保持原样
        val hasBlankInput = text.isBlank() || oldName.isBlank() || newName.isBlank()
        if (hasBlankInput || oldName == newName) return text
        val pattern = Regex(Regex.escape(PREFIX + oldName) + "(?![A-Za-z0-9_])")
        // 注意：正则**替换串**里的 `$` 是特殊字符（`$1` = 分组引用），
        // 因此替换文本必须转义，否则 `$$NEW` 会被当作分组引用解析，替换结果错误。
        return pattern.replace(text, Regex.escapeReplacement(PREFIX + newName))
    }

    /** 提供方配置里与凭证相关的字段，逐个做引用替换（值不做任何其它改动）。 */
    private fun renameInProvider(provider: ProviderSetting, oldName: String, newName: String): ProviderSetting =
        when (provider) {
            is ProviderSetting.OpenAI -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
            is ProviderSetting.Claude -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
            is ProviderSetting.Google -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
            is ProviderSetting.Backend ->
                provider.copy(
                    token = renameInText(provider.token, oldName, newName),
                    password = renameInText(provider.password, oldName, newName),
                )
            else -> provider
        }

    @Suppress("CyclomaticComplexMethod")  // 凭证/重试链路本质是分支枚举，拆分反而打散可读性；复杂度优化列入后续专项
    private fun renameInSearchOptions(
        options: SearchServiceOptions,
        oldName: String,
        newName: String,
    ): SearchServiceOptions = when (options) {
        is SearchServiceOptions.ZhipuOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.DoubaoOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.TavilyOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.ExaOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.SearXNGOptions -> options.copy(password = renameInText(options.password, oldName, newName))
        is SearchServiceOptions.LinkUpOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.BraveOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.MetasoOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.OllamaOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.PerplexityOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.FirecrawlOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.JinaOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.BochaOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.RikkaHubOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.GrokOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.TinyfishOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.SerperOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        is SearchServiceOptions.DeepSeekOptions -> options.copy(apiKey = renameInText(options.apiKey, oldName, newName))
        else -> options
    }

    @Suppress("CyclomaticComplexMethod")  // 凭证/重试链路本质是分支枚举，拆分反而打散可读性；复杂度优化列入后续专项
    internal fun renameCredentialReferencesInSettings(
        settings: Settings,
        oldName: String,
        newName: String,
    ): Pair<Settings, Int> {
        var changed = 0
        val mediaProviders = settings.mediaGenerationProviders.map { provider ->
            provider.copyProvider(apiKey = renameInText(provider.apiKey, oldName, newName)).also {
                if (it != provider) changed++
            }
        }
        val searchServices = settings.searchServices.map { options ->
            renameInSearchOptions(options, oldName, newName).also { if (it != options) changed++ }
        }
        val asrProviders = settings.asrProviders.map { provider ->
            val renamed = when (provider) {
                is ASRProviderSetting.OpenAIRealtime -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is ASRProviderSetting.DashScope -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is ASRProviderSetting.Volcengine -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is ASRProviderSetting.MiMo -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is ASRProviderSetting.Step -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
            }
            if (renamed != provider) changed++
            renamed
        }
        val ttsProviders = settings.ttsProviders.map { provider ->
            val renamed = when (provider) {
                is TTSProviderSetting.OpenAI -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.Gemini -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.MiniMax -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.SystemTTS -> provider
                is TTSProviderSetting.Qwen -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.Groq -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.XAI -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.MiMo -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.ElevenLabs -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.FishAudio -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.Step -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
                is TTSProviderSetting.Volcengine -> provider.copy(apiKey = renameInText(provider.apiKey, oldName, newName))
            }
            if (renamed != provider) changed++
            renamed
        }
        val s3Config = settings.s3Config.copy(
            secretAccessKey = renameInText(settings.s3Config.secretAccessKey, oldName, newName),
        ).also { if (it != settings.s3Config) changed++ }
        val s3Configs = settings.s3Configs.map { config ->
            config.copy(secretAccessKey = renameInText(config.secretAccessKey, oldName, newName)).also {
                if (it != config) changed++
            }
        }
        val uploadS3Config = settings.uploadS3Config.copy(
            secretAccessKey = renameInText(settings.uploadS3Config.secretAccessKey, oldName, newName),
        ).also { if (it != settings.uploadS3Config) changed++ }
        val networkSetting = settings.networkSetting.copy(
            proxyPassword = renameInText(settings.networkSetting.proxyPassword, oldName, newName),
        ).also { if (it != settings.networkSetting) changed++ }
        val webBridgePassword = renameInText(settings.webBridgePassword, oldName, newName).also {
            if (it != settings.webBridgePassword) changed++
        }
        val webServerAccessPassword = renameInText(settings.webServerAccessPassword, oldName, newName).also {
            if (it != settings.webServerAccessPassword) changed++
        }
        val webBridgePrivateKeyPath = renameInText(settings.webBridgePrivateKeyPath, oldName, newName).also {
            if (it != settings.webBridgePrivateKeyPath) changed++
        }
        return settings.copy(
            mediaGenerationProviders = mediaProviders,
            searchServices = searchServices,
            asrProviders = asrProviders,
            ttsProviders = ttsProviders,
            s3Config = s3Config,
            s3Configs = s3Configs,
            uploadS3Config = uploadS3Config,
            networkSetting = networkSetting,
            webBridgePassword = webBridgePassword,
            webServerAccessPassword = webServerAccessPassword,
            webBridgePrivateKeyPath = webBridgePrivateKeyPath,
        ) to changed
    }

    internal fun renameTelegramCredentialReferences(
        config: TelegramBotConfig,
        oldName: String,
        newName: String,
    ): Pair<TelegramBotConfig, Int> {
        val renamed = config.copy(
            token = renameInText(config.token, oldName, newName),
            proxyPassword = renameInText(config.proxyPassword, oldName, newName),
        )
        val changedFields = listOf(config.token to renamed.token, config.proxyPassword to renamed.proxyPassword)
            .count { (before, after) -> before != after }
        return renamed to changedFields
    }

    /** Rename all credential references stored on a saved SSH host. */
    internal fun renameSshHostReferences(
        host: SshHostEntity,
        oldName: String,
        newName: String,
    ): Pair<SshHostEntity, Int> {
        val renamed = host.copy(
            password = host.password?.let { renameInText(it, oldName, newName) },
            privateKey = host.privateKey?.let { renameInText(it, oldName, newName) },
            passphrase = host.passphrase?.let { renameInText(it, oldName, newName) },
            vaultCredentialRef =
                if (host.vaultCredentialRef?.trim() == oldName) newName else host.vaultCredentialRef,
        )
        val changedFields =
            listOf(
                host.password to renamed.password,
                host.privateKey to renamed.privateKey,
                host.passphrase to renamed.passphrase,
                host.vaultCredentialRef to renamed.vaultCredentialRef,
            ).count { (before, after) -> before != after }
        return renamed to changedFields
    }

    /**
     * 全量同步：把配置中所有 `$$oldName` 引用改为 `$$newName`。
     *
     * @return 发生改动的位置数（0 = 没有任何配置引用它）
     */
    suspend fun renameEverywhere(
        settingsStore: SettingsStore,
        sshHostRepository: SshHostRepository,
        telegramBotPreferences: TelegramBotPreferences,
        oldName: String,
        newName: String,
    ): Int {
        if (oldName.isBlank() || newName.isBlank() || oldName == newName) return 0
        var changed = 0

        val settings = settingsStore.settingsFlow.first()

        // 1) 模型提供方
        val newProviders =
            settings.providers.map { provider ->
                val renamed = renameInProvider(provider, oldName, newName)
                if (renamed != provider) changed++
                renamed
            }

        // 2) MCP 服务器出站头部值
        val newMcpServers =
            settings.mcpServers.map { server ->
                val headers =
                    server.commonOptions.headers.map { (n, v) ->
                        val nv = renameInText(v, oldName, newName)
                        if (nv != v) changed++
                        n to nv
                    }
                server.clone(commonOptions = server.commonOptions.copy(headers = headers))
            }

        // 3) S3 / WebDAV 备份凭据
        fun renameDav(config: me.rerere.rikkahub.data.datastore.WebDavConfig) =
            config.copy(password = renameInText(config.password, oldName, newName)).also {
                if (it != config) changed++
            }

        // 4) 本地 MCP 令牌引用（按名字整体匹配）
        val newLocalMcp =
            settings.localMcpProfiles.map { profile ->
                if (profile.authTokenRef.trim() == oldName) {
                    changed++
                    profile.copy(authTokenRef = newName)
                } else {
                    profile
                }
            }

        // 5) Web 桥引用（按名字整体匹配）
        val newWebBridgeRef =
            if (settings.webBridgeCredentialRef.trim() == oldName) {
                changed++
                newName
            } else {
                settings.webBridgeCredentialRef
            }

        settingsStore.update { current ->
            val (credentialFields, credentialChanges) = renameCredentialReferencesInSettings(current, oldName, newName)
            changed += credentialChanges
            current.copy(
                providers = newProviders,
                mcpServers = newMcpServers,
                s3Config = credentialFields.s3Config,
                s3Configs = credentialFields.s3Configs,
                uploadS3Config = credentialFields.uploadS3Config,
                webDavConfig = renameDav(current.webDavConfig),
                webDavConfigs = current.webDavConfigs.map { renameDav(it) },
                localMcpProfiles = newLocalMcp,
                webBridgeCredentialRef = newWebBridgeRef,
                mediaGenerationProviders = credentialFields.mediaGenerationProviders,
                searchServices = credentialFields.searchServices,
                asrProviders = credentialFields.asrProviders,
                ttsProviders = credentialFields.ttsProviders,
                networkSetting = credentialFields.networkSetting,
                webBridgePassword = credentialFields.webBridgePassword,
                webBridgePrivateKeyPath = credentialFields.webBridgePrivateKeyPath,
                webServerAccessPassword = credentialFields.webServerAccessPassword,
            )
        }

        val telegram = telegramBotPreferences.current()
        val (_, telegramChanges) = renameTelegramCredentialReferences(telegram, oldName, newName)
        if (telegramChanges > 0) {
            telegramBotPreferences.update { current ->
                renameTelegramCredentialReferences(current, oldName, newName).first
            }
            changed += telegramChanges
        }

        // 6) 已保存 SSH 主机：Vault 名称引用及三个支持 `$$name` 的认证字段。
        sshHostRepository.getAll().forEach { host ->
            val (renamedHost, changedFields) = renameSshHostReferences(host, oldName, newName)
            if (changedFields > 0) {
                sshHostRepository.upsert(renamedHost)
                changed += changedFields
            }
        }

        return changed
    }
}
