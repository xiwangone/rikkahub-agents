package me.rerere.rikkahub.data.vault

import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.SshHostRepository

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
        if (text.isBlank() || oldName.isBlank() || newName.isBlank() || oldName == newName) return text
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

    /**
     * 全量同步：把配置中所有 `$$oldName` 引用改为 `$$newName`。
     *
     * @return 发生改动的位置数（0 = 没有任何配置引用它）
     */
    suspend fun renameEverywhere(
        settingsStore: SettingsStore,
        sshHostRepository: SshHostRepository,
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
        fun renameS3(config: me.rerere.rikkahub.data.sync.s3.S3Config) =
            config.copy(secretAccessKey = renameInText(config.secretAccessKey, oldName, newName)).also {
                if (it != config) changed++
            }
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
            current.copy(
                providers = newProviders,
                mcpServers = newMcpServers,
                s3Config = renameS3(current.s3Config),
                s3Configs = current.s3Configs.map { renameS3(it) },
                webDavConfig = renameDav(current.webDavConfig),
                webDavConfigs = current.webDavConfigs.map { renameDav(it) },
                localMcpProfiles = newLocalMcp,
                webBridgeCredentialRef = newWebBridgeRef,
            )
        }

        // 6) 已保存的 SSH 主机（按名字整体匹配；实体需逐条写回）
        sshHostRepository.getAll().forEach { host ->
            if (host.vaultCredentialRef?.trim() == oldName) {
                sshHostRepository.upsert(host.copy(vaultCredentialRef = newName))
                changed++
            }
        }

        return changed
    }
}
