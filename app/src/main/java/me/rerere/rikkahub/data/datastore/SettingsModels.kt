package me.rerere.rikkahub.data.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.data.ai.mcp.LocalMcpProfile
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.get
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val networkSetting: NetworkSetting = NetworkSetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    /** 执行后端（P4 连接中枢 MVP）：local（默认本机）/ backend / ecs 等——AI 执行通道 */
    val executionBackend: String = "local",
    /** 后端连接列表（已通用化）：可保存/切换/删除——backend/SSH/自定义统一 */
    val backendConnections: List<me.rerere.rikkahub.data.model.BackendConnection> = emptyList(),
    val fastModelId: Uuid = Uuid.random(),    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    /** Automatically create a title when a conversation completes. */
    val autoGenerateTitle: Boolean = true,
    /** 流式响应失败重试次数上限（0-10，默认 5） */
    val responseStreamMaxRetries: Int = 5,
    /** 流式响应失败自动重试总开关（默认开） */
    val enableAutoRetry: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    /** 本地 OCR 开关：优先使用设备端 ML Kit 识别，失败时回退 AI OCR（默认开） */
    val ocrLocalEnabled: Boolean = true,
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    /** 自动压缩开关：长对话接近 context 上限时自动触发压缩（默认关） */
    val autoCompressEnabled: Boolean = false,
    /** 自动压缩触发阈值：当前对话估算 token 占模型 context 的百分比 */
    val autoCompressThreshold: Int = 80,
    /** 对话累计 token 上限：会话累计输入 token 达到该值即自动压缩（0 = 不启用） */
    val autoCompressTokenLimit: Long = 0L,
    /** 自动压缩基准 token 数：百分比模式下模型 context 基准（0 = 沿用默认 512K 估算） */
    val autoCompressTokenBase: Long = 1_000L * 1000L,
    /** 自动压缩模式：0 = 百分比模式（基准×阈值），1 = token 消耗模式（累计上限） */
    val autoCompressMode: Int = 0,
    /**
     * 手动填写的上下文窗口大小（token）。0 = 未填写，此时使用模型上报的窗口；
     * 仅影响占用比例的展示，与自动压缩阈值互不影响。
     */
    val contextWindowSize: Long = 0L,
    /** 工具输出限制开关：启用后对工具输出进行截断（默认关） */
    val toolOutputEnabled: Boolean = true,

    /** 工具输出落盘阈值（字符数）：超过后截断落盘 + 返回预览，范围 4K-20K */
    val toolOutputMaxChars: Int = 8 * 1000,

    /**
     * 紧凑阈值（字符数）：**仅**命中助手 [Assistant.toolOutputCompactTools] 的工具使用，
     * 其余工具仍走 [toolOutputMaxChars] 兜底。用于把 diagnostics / 网页抓取这类
     * “大嘴”工具的输出收紧，同时不动其他工具。
     */
    val toolOutputCompactMaxChars: Int = 2 * 1000,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
    /**
     * IDs of built-in providers the user explicitly removed via long-press. The re-seed
     * pass on settings load skips these so deletions stick across app restarts. Without
     * this gate, deleting a default provider would just re-add it on next read.
     */
    val deletedBuiltInProviderIds: Set<Uuid> = emptySet(),
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    /**
     * Legacy: names of bundled skills previously auto-seeded into the default assistants'
     * enabledSkills. Kept only so existing user data still round-trips through read/write;
     * skills are no longer ever auto-enabled (see the load pass above), so this set has no
     * runtime effect and new installs store an empty set.
     */
    val autoEnabledDefaultSkills: Set<String> = emptySet(),
    /**
     * 用户显式删除的内置技能名（由 SkillManager.deleteSkill 记录）。
     *
     * 删除会连同技能目录内的哨兵文件一起清掉，仅凭磁盘状态无法区分「从未安装」与
     * 「装过又被删」，会导致重启后又被种回来。该记录存放于技能目录之外，
     * 重新安装时清除；非内置技能不会被记录。
     */
    val deletedBundledSkills: Set<String> = emptySet(),
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    /**
     * 命名子代理配置：subagent_dispatch 按名分发，默认空列表。
     */
    val subAgents: List<SubAgentProfile> = emptyList(),
    /**
     * 子代理默认工作区：子代理未显式指定工作区时用哪个（null = 跟随父助手）。
     * 与 "工具调用自动批准" 搭配使用：把子代理放进独立工作区可避免与主对话同区写互踩。
     */
    val subAgentDefaultWorkspaceId: Uuid? = null,
    /**
     * 前台派发的子代理完成后，是否也把结果作为一条消息投进父会话。
     *
     * 默认关：前台结果本已作为**工具返回值**交给父助手，再投一条会重复。打开则保留一条
     * 会话记录（便于回溯）。后台派发**始终**回执，不受此项影响。
     */
    val subAgentForegroundReceipt: Boolean = false,
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    /** Upload destination for generated media; distinct from backup-provider S3 settings. */
    val uploadS3Config: S3Config = S3Config(),
    /** 多 WebDAV 配置（可保存/切换/删除）。空时回退用 [webDavConfig] 单配置（旧数据兼容）。 */
    val webDavConfigs: List<WebDavConfig> = emptyList(),
    /** 当前生效的 WebDAV 配置 id（空 = 用 [webDavConfig] / 列表首个） */
    val activeWebDavConfigId: String? = null,
    /** 多 S3 配置（可保存/切换/删除）。空时回退用 [s3Config] 单配置（旧数据兼容）。 */
    val s3Configs: List<S3Config> = emptyList(),
    /** 当前生效的 S3 配置 id（空 = 用 [s3Config] / 列表首个） */
    val activeS3ConfigId: String? = null,
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    /** 本地 MCP Server（供 Backend serve 挂载设备工具）开关 */
    val localMcpServerEnabled: Boolean = false,
    /** 本地 MCP Server 可配置的多 profile（默认空；用户自填 + 勾选工具） */
    val localMcpProfiles: List<LocalMcpProfile> = emptyList(),
    /** 当前生效的本地 MCP profile id */
    val activeLocalMcpProfileId: String? = null,
    /** Web 桥全局配置：SSH 反向隧道到 ECS（手机 web 服务映射到远端端口） */
    val webBridgeEcsHost: String = "",
    val webBridgeEcsUser: String = "root",
    val webBridgeEcsPort: Int = 22,
    val webBridgeRemotePort: Int = 8080,
    val webBridgeLocalPort: Int = 8080,
    val webBridgePrivateKeyPath: String = "",
    val webBridgePassword: String = "",
    /** Web 桥 SSH 认证：Vault 凭证引用（私钥或密码条目名，优先于明文密码/私钥路径） */
    val webBridgeCredentialRef: String = "",
    val webServerJwtEnabled: Boolean = false,
    /** Web 服务监听范围：loopback（仅本机，默认）/ lan（局域网与内网）/ any（所有接口） */
    val webServerListenScope: String = "loopback",
    /** 允许访问的网段白名单（CIDR，逗号分隔；空 = 不限制）。仅非 loopback 时生效 */
    val webServerAllowedNetworks: String = "",
    val webServerAccessPassword: String = "",
    /**
     * 全局「网段预设」：Web 服务器 / 调试接口 / 本地 MCP 等所有需要填 CIDR 的入口共用一套，
     * 免去每处重复手输。空 = 尚未保存任何预设。
     */
    val networkCidrPresets: List<String> = emptyList(),
    /** 设置页快捷区：用户自选的有序入口 id 列表（id 见 SettingCatalog）。 */
    val settingShortcutIds: List<String> = emptyList(),
    /** 沙箱包管理器镜像（空 = 默认源）：apk / apt / pip / npm。 */
    val workspaceApkMirror: String = "",
    val workspaceAptMirror: String = "",
    val workspacePipMirror: String = "",
    val workspaceNpmMirror: String = "",
    /** 手机存储挂载到沙箱 /sdcard（可选挂载，默认关；写操作仍走审批白名单）。 */
    val workspaceSdcardEnabled: Boolean = false,
    /** chroot 后端开关（实验性，root 设备，默认关）：开 → shell 经 chroot-run 执行。 */
    val workspaceChrootEnabled: Boolean = false,
    /** 工作区资源画像缓存（JSON 字符串：id -> {rootBytes, packageCount, kernel, at}）。 */
    val workspaceStatsCache: String = "{}",
    val webServerLocalhostOnly: Boolean = true,
    val aiLogLevel: AiLogLevel = AiLogLevel.INFO,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    /** 备份包整包口令加密总开关（WebDAV/S3/本地全通道生效；默认关=明文兼容旧包）。 */
    val backupEncryptionEnabled: Boolean = false,
    /** 记住的备份加密口令（AndroidKeyStore AES-GCM 密文，非明文）。空 = 未设置/未记住。 */
    val backupEncryptionPasswordEnc: String = "",
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
) {
    /**
     * 当前生效的 WebDAV 配置：
     * 优先 activeWebDavConfigId 命中；否则列表非空取首个；否则回退旧单配置 [webDavConfig]。
     */
    fun activeWebDavConfig(): WebDavConfig =
        (webDavConfigs.firstOrNull { it.id == activeWebDavConfigId }
            ?: webDavConfigs.firstOrNull()
            ?: webDavConfig).withLegacyExpanded()

    /** 当前生效的 S3 配置（同 [activeWebDavConfig] 逻辑）。 */
    fun activeS3Config(): S3Config =
        (s3Configs.firstOrNull { it.id == activeS3ConfigId }
            ?: s3Configs.firstOrNull()
            ?: s3Config).withLegacyExpanded()

    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class AiLogLevel(val preferenceName: String) {
    @SerialName("off")
    OFF("off"),
    @SerialName("info")
    INFO("info"),
    @SerialName("debug")
    DEBUG("debug");

    companion object {
        fun fromPreference(value: String?): AiLogLevel = entries.firstOrNull { it.preferenceName == value } ?: INFO
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class NetworkSetting(
    val userAgent: String = "",
    val proxyUrl: String = "",
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    val hostAddressOverrides: List<HostAddressOverride> = emptyList(),
    // ── 内嵌隧道（per-app，不接管系统 VPN / DNS）──────────────────────────
    // 连接开关只存在于运行期（见 `TunnelState`）：重开 App 不自动连，所以这里不落盘 enabled。
    /** 隧道类型：tailscale / wireguard。 */
    val tunnelType: String = TUNNEL_TAILSCALE,
    /** 控制面 URL（自填，不写死；如自建 Headscale 或默认 Tailscale）。 */
    val tailscaleControlUrl: String = "",
    /** 认证密钥（preauth key，tskey-auth-...）。 */
    val tailscaleAuthKey: String = "",
    /** 节点在 tailnet 中显示的名字。 */
    val tailscaleHostname: String = "rikkahub",
    /** WireGuard 配置（.conf 原文，手工粘贴）。 */
    val wireguardConfig: String = "",
) {
    companion object {
        const val TUNNEL_TAILSCALE = "tailscale"
        const val TUNNEL_WIREGUARD = "wireguard"
    }
}

/**
 * Pins [host] to a fixed [address] at the DNS layer, so requests for that host skip the
 * system resolver. [address] must be an IP literal; a hostname is ignored.
 *
 * [enabled] lets an entry be switched off without losing its value — useful when a pinned
 * address turns out to be unreachable and you want to fall back to the system resolver for a
 * while, or when comparing two addresses.
 */
@Serializable
data class HostAddressOverride(
    val host: String = "",
    val address: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = true,
    /** Keep the conversation drawer ordered by its original creation time instead of recent activity. */
    val sortConversationsByCreatedAt: Boolean = false,
    /** Persisted reminder pause deadline (epoch millis); zero means not paused. */
    val showUpdatesUntilEpochMillis: Long = 0L,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val enableFloatingChatWindow: Boolean = false,
    /**
     * 工具面裁剪（冷档空 schema 拦截 + 温档描述收敛）。
     *
     * 它影响的是「注入给模型的工具描述与参数表」这一视图层，故归入显示设置；
     * 与 `ToolSurfacePolicy.TRIM_ENABLED`（编译期总开关）取**与**关系，任一关闭即完全不裁剪。
     *
     * ⚠ **默认关闭**：关闭时 = 治理前的行为（所有工具都带完整描述与参数表）。
     * 之所以默认关：这是「按需 schema」这类会影响所有用户默认行为的能力，属可选项 ——
     * 需要的人（例如重度工具调用的助手）再打开，避免替所有人改变默认。
     */
    val toolSurfaceTrimming: Boolean = false,

    /**
     * 工具调用统计（次数/耗时/解锁/注入集哈希）。
     *
     * **默认关**：这些数据只服务于“按真实使用调优工具面”，普通用户既不需要、
     * 也不应为埋点买单；需要时在设置里打开即可（诊断的 usage / tool_scope 依赖它）。
     */
    val toolStatsEnabled: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = true,
    /** 输入框功能按钮行（Zap/LockKey/ASR 等）默认折叠；用户展开过则记住，下次启动保持 */
    val featureBarCollapsed: Boolean = true,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    /**
     * 附件**内联**上限（解析后正文的字符数）。
     *
     * 不超过该值时照旧把整篇正文内联进提示词（`<UploadFile>` 代码块，零行为变化）；
     * 超过时改为只发引用（name + path + 首尾摘要），由模型按 path 用工作区文件工具自取全文。
     *
     * 默认 8000 字符：仓内压缩块预算为 6000 token/块、长粘贴阈值 1000 字符，
     * 8000 字符按 3 字符/token 折约 2.7K token，属「内联不心疼」区间；且仅在会话确实拥有
     * 工作区文件读取工具时才走引用式（否则仍整篇内联，见 `DocumentAsPromptTransformer`），
     * 默认值本身即是保守上限。
     */
    val attachmentInlineThreshold: Int = 8000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableQuoteHighlight: Boolean = false,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
    val chatInputButtons: ChatInputButtons = ChatInputButtons(),
    /** 渲染性能：自动（按设备能力）/ 流畅优先 / 完整优先；完整优先等价于关闭全部合并与节流 */
    val renderPerformance: RenderPerformance = RenderPerformance.AUTO,
)

@Serializable
enum class RenderPerformance {
    @SerialName("auto")
    AUTO,

    @SerialName("smooth")
    SMOOTH,

    @SerialName("full")
    FULL,
}

@Serializable
enum class InputButtonType {
    @SerialName("model_selector") MODEL_SELECTOR,
    @SerialName("search_picker") SEARCH_PICKER,
    @SerialName("reasoning_button") REASONING_BUTTON,
    @SerialName("mcp_picker") MCP_PICKER,
    @SerialName("local_tools_picker") LOCAL_TOOLS_PICKER,
    @SerialName("translate_picker") TRANSLATE_PICKER,
}

@Serializable
data class InputButtonItem(
    val type: InputButtonType,
    val enabled: Boolean = true
)

@Serializable
data class ChatInputButtons(
    val buttons: List<InputButtonItem> = listOf(
        InputButtonItem(InputButtonType.MODEL_SELECTOR, true),
        InputButtonItem(InputButtonType.SEARCH_PICKER, true),
        InputButtonItem(InputButtonType.REASONING_BUTTON, true),
        InputButtonItem(InputButtonType.MCP_PICKER, true),
        InputButtonItem(InputButtonType.LOCAL_TOOLS_PICKER, true),
        InputButtonItem(InputButtonType.TRANSLATE_PICKER, true),
    )
)

@Serializable
data class WebDavConfig(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.SETTINGS,
        BackupItem.AVATARS,
    ),
) {
    val displayName: String
        get() = name.ifBlank { if (url.isBlank()) "未命名" else url.removePrefix("https://").removePrefix("http://").substringBefore('/') }

    /**
     * 展开旧聚合项 FILES 为细分项（SKILLS+CHAT_FILES+FONTS_IMAGES）。
     * 旧版配置勾 FILES = 全选文件大头；细分项成熟后 UI 不再展示 FILES，
     * 但存量配置可能仍含 FILES，执行前展开避免漏带或误导。
     */
    fun withLegacyExpanded(): WebDavConfig {
        if (BackupItem.FILES !in items) return this
        val expanded =
            buildList {
                items.forEach {
                    if (it == BackupItem.FILES) {
                        add(BackupItem.SKILLS)
                        add(BackupItem.CHAT_FILES)
                        add(BackupItem.FONTS_IMAGES)
                    } else {
                        add(it)
                    }
                }
            }.distinct()
        return copy(items = expanded)
    }

    @Serializable
    enum class BackupItem {
        /** 聊天/记忆/设置数据库（rikka_hub.db，含 vault 密文、SSH 主机、调度任务） */
        DATABASE,
        /** 设置与偏好（settings.json：助手/模型/外观/备份通道等；apiKey 明文随包） */
        SETTINGS,
        /** 头像图片（files/avatars/，与聊天图分离，体积小） */
        AVATARS,
        /** 工作区文档与状态目录（排除 tmp/.git/大件） */
        WORKSPACE_DOCS,
        /** 已装技能（files/skills/，递归） */
        SKILLS,
        /** 聊天图片/附件（files/upload/，体积大头，可选） */
        CHAT_FILES,
        /** 自定义字体与贴图（files/fonts/ files/images/） */
        FONTS_IMAGES,
        /** 工具输出缓存（files/tool_outputs/） */
        TOOL_OUTPUTS,
        /** 兼容旧配置的聚合项：等价于旧 FILES 的默认勾选集合（SKILLS+CHAT_FILES+FONTS_IMAGES） */
        FILES,

        // ── 按域逻辑备份（数据库/设置的细分粒度）──
        // 这些项不打包原始 sqlite / settings.json，而是把域内容转成**明文 JSON** 放进包的
        // migration/ 段，再由口令加密整包；恢复时用本机 Keystore 重新加密写回。
        // 因此它们天然跨设备可用（区别于 DATABASE 里的本机密文）。
        /** 提供商与模型配置（来自 settings.json 的 providers，含 apiKey） */
        PROVIDERS,
        /** 凭证库条目（导出时逐条解密；恢复时用本机 Keystore 重新加密） */
        VAULT_CREDENTIALS,
        /** SSH 主机（导出时解密 password/privateKey；恢复时重新加密） */
        SSH_HOSTS,
        /** 定时任务（scheduled_jobs 表，仅配置与游标，不含运行记录） */
        SCHEDULED_JOBS,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * 该会话实际生效的助手 —— 与生成链路同口径：会话绑定的助手优先，取不到才回退当前助手。
 */
fun Settings.resolveAssistantFor(assistantId: Uuid?): Assistant {
    return assistantId?.let { getAssistantById(it) } ?: getCurrentAssistant()
}

/**
 * 会话内 UI（标题、发送前置校验、输入区能力门）统一采用的模型解析口径 —— 与生成链路同序：
 * 会话指定（仅当助手允许会话级模型）→ 助手指定 → 全局默认；助手按会话绑定解析。
 * 缺了会话一级就会“标题显示 A、实际用 B”，故这里做成单一来源。
 */
fun Settings.findChatModelFor(
    conversationChatModelId: Uuid?,
    conversationAssistantId: Uuid? = null,
): Model? {
    val assistant = resolveAssistantFor(conversationAssistantId)
    val id = conversationChatModelId?.takeIf { assistant.allowPerConversationModel }
        ?: assistant.chatModelId ?: chatModelId
    return findModelById(id)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.findAssistantById(id: Uuid): Assistant? {
    return this.assistants.firstOrNull { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return ttsProviders.find { it.id == selectedTTSProviderId } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = "",
        // The agent-core skill bundle (SOUL/HEARTBEAT/TOOLS) ships with the app and is what
        // teaches every model "you are running on RikkaHub Agents, here are the tools, here is how
        // to avoid loops". Auto-enabling it on default assistants means new users get an
        // agent-aware model out of the box without having to discover the skill toggle.
        enabledSkills = setOf("agent-core"),
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent(),
        enabledSkills = setOf("agent-core"),
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    )
)


/** Whether update reminders are currently visible; expired pauses are treated as resumed. */
fun DisplaySetting.shouldShowUpdateReminders(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
    showUpdates && showUpdatesUntilEpochMillis <= nowEpochMillis
