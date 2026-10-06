package me.rerere.rikkahub.data.ai.tools

/**
 * 工具面的"温度"分档：决定每个工具注入给模型时携带多少 schema。
 *
 * 目标是压缩常驻提示体积——高频工具保留完整说明，低频工具只留一行用途，
 * 需要时再由检索类工具拉取完整参数（渐进式披露）。
 *
 * 注意：档位**只影响注入给模型的视图**，不影响工具能否被调用；也不会移除任何工具。
 */
enum class SurfaceTier {
    /** 高频：保留完整 description 与参数表。 */
    HOT,

    /** 常用：保留一行用途，参数表收敛为占位。 */
    WARM,

    /** 低频：只保留一行用途。 */
    COLD,
}

/** 档位判定的来源：解释“这个工具为什么落在这一档”。 */
enum class TierSource {
    /** 命中助手级降温名单（助手级 extraColdTools 配置）。 */
    ASSISTANT_EXTRA_COLD,

    /** 命中策略热档（ToolSurfacePolicy.HOT）。 */
    POLICY_HOT,

    /** 命中策略冷档单件。 */
    POLICY_COLD_EXTRA,

    /** 命中策略冷档家族前缀。 */
    POLICY_COLD_PREFIX,

    /** 未命中任何规则 → 默认温档。 */
    DEFAULT_WARM,

    /** 落在助手级工具白名单之外（白名单 + 精简同时开启时，名单外降冷水而非硬删）。 */
    OUTSIDE_ASSISTANT_SCOPE,

    /** 当前 UI 场景临时上调（场景相关的工具给完整 schema）。 */
    SCENE_RAISED,

    /** 当前 UI 场景临时下调（场景无关的热档工具收敛描述，参数表保留）。 */
    SCENE_LOWERED,
}

/**
 * UI 场景：描述用户当前在哪个页面，由 UI 层（RouteActivity 观测导航栈）写入，
 * 数据层（ChatService 装配工具时）读取。用于 WARM 档按场景动态调整。
 */
enum class UiScene {
    /** 聊天页（默认场景）。 */
    CHAT,

    /** 图片生成页。 */
    IMAGE_GEN,

    /** 终端页 / SSH 会话页。 */
    TERMINAL,

    /** 文件管理 / 工作区浏览页。 */
    FILES,

    /** 未知场景：回退到纯静态判定（等价于不传场景）。 */
    UNKNOWN,
}

/** 档位判定结果：档位 + 来源。 */
data class TierDecision(
    val tier: SurfaceTier,
    val source: TierSource,
)

/**
 * 档位判定。
 *
 * 依据 device 侧使用统计（`ssh_exec_saved` / `vault_*` / `workspace_*` / `device_info` 等长期居前）
 * 与"家族整体低频"的观察：Telegram、工作流、外部自动化、密钥库、NFC 等家族极少被调用。
 */
object ToolSurfacePolicy {

    /**
     * 工具面裁剪总开关（COLD 空 schema 拦截 + WARM 描述收敛）。
     *
     * 治理约定要求「所有裁剪由一个开关控制，出问题一键关」。此处用**单点常量**实现：
     * 置 false 即恢复完整 description/参数表（等价于未启用裁剪），回退改动集中在这一行。
     *
     * 与设置项 `DisplaySetting.toolSurfaceTrimming` 取**与**关系；后者**默认关闭** ——
     * 即出厂行为等于"治理前"（工具面不裁剪），需要的人再打开，不替所有用户改默认。
     */
    const val TRIM_ENABLED = true

    /** 高频工具：完整 schema。保持这一集合稳定，避免注入集抖动影响前缀缓存。 */
    val HOT: Set<String> =
        setOf(
            // 远程执行
            "ssh_exec_saved", "ssh_upload", "ssh_download", "ssh_job_poll", "ssh_presets", "list_ssh_hosts",
            // 工作区与文件
            "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "workspace_run_background", "workspace_background_status", "workspace_background_kill",
            "read_file", "write_text_file", "list_files", "find_files",
            // 凭证
            "vault_http_exec", "vault_export_env", "vault_credential_names",
            // vault_ssh_exec 调用频次居前，属高频而非低频，故留在热档：
            // 移到冷档会让每会话首次调用都多一次 schema 往返。
            "vault_ssh_exec",
            // 设备与诊断
            "device_info", "diagnostics",
            // 网络与协作
            "web_fetch", "subagent_dispatch", "memory_search", "memory_tool",
            // 屏幕与特权
            "shizuku_exec", "read_window_tree",
        )

    /** 低频家族：整体降到冷档（这些家族的调用在统计中长期接近零）。 */
    private val COLD_PREFIXES =
        listOf(
            "telegram_",
            "workflow_",
            "external_automation_",
            "keystore_",
            "nfc_",
        )

    /** 低频单件（不属于上述家族，但同样极少被调用）。 */
    private val COLD_EXTRAS =
        setOf(
            "scan_media", "set_wallpaper", "record_audio", "get_media_status",
            "appops_get", "appops_set", "open_wifi_settings",
            "vault_export_loadcreds", "vault_import_loadcreds", "vault_compare_loadcreds",
            "vault_gen_key", "vault_deploy_ssh_key", "vault_credential_prepare",
            "vault_credential_update", "vault_credential_delete", "vault_credential_audit",
            "vault_credential_meta",
            "whisper_status", "check_app_updates", "generate_bug_report",
        )

    /**
     * 场景临时上调表：场景 → 在该场景下临时升为 HOT 的工具名。
     *
     * 只列**静态非 HOT** 的工具（静态已是 HOT 的无需重复列，判定逻辑会自动跳过）。
     * 纯数据表，单测覆盖每个场景；`UNKNOWN` 场景不做任何调整。
     */
    val SCENE_HOT: Map<UiScene, Set<String>> =
        mapOf(
            // 图片生成页：图片族目前静态全在 COLD/WARM，每次调用都要先 get_tool_schema
            // 解锁（多一次往返）。进页面时临时给完整 schema，离开恢复。
            UiScene.IMAGE_GEN to
                setOf(
                    "scan_media", "set_wallpaper", "get_media_status",
                    "show_image", "take_photo",
                ),
            // 以下场景首批不填（空表 = 无调整），后续按需补充：
            UiScene.TERMINAL to emptySet(),
            UiScene.FILES to emptySet(),
            UiScene.CHAT to emptySet(),
            UiScene.UNKNOWN to emptySet(),
        )

    /**
     * 场景临时下调表：场景 → 在该场景下从 HOT 临时降为 WARM 的工具名。
     *
     * 只对**静态 HOT** 的工具生效（静态非 HOT 的不受影响）；WARM 档保留完整参数表，
     * 模型仍可直接调用，只是描述收敛——因此下调不丢正确性，只省 token。
     * 用户显式配置（extraCold/白名单）优先级高于场景，不受影响。
     */
    val SCENE_WARM: Map<UiScene, Set<String>> =
        mapOf(
            // 图片生成页：SSH / 凭证 / 特权 / 设备诊断与图片生成无关，临时收敛描述。
            // 工作区与文件工具保留 HOT（用户可能把生成的图存文件）。
            UiScene.IMAGE_GEN to
                setOf(
                    "ssh_exec_saved", "ssh_upload", "ssh_download",
                    "ssh_job_poll", "ssh_presets", "list_ssh_hosts",
                    "vault_http_exec", "vault_export_env",
                    "vault_credential_names", "vault_ssh_exec",
                    "shizuku_exec", "read_window_tree",
                    "device_info", "diagnostics",
                ),
            UiScene.TERMINAL to emptySet(),
            UiScene.FILES to emptySet(),
            UiScene.CHAT to emptySet(),
            UiScene.UNKNOWN to emptySet(),
        )

    /**
     * 白名单模式下的保命工具：**零副作用的自救层**（求援 / 列工具 / 取参数表），
     * 白名单收窄时始终注入，避免“看不见工具也取不回参数表”的死局。
     *
     * 单一来源：装配侧（ChatToolFactory 的范围过滤）与只读诊断（diagnostics kind=tool_scope）共用，
     * 避免两处硬编码各自漂移。⚠ 只放**不产生副作用**的元工具——执行类工具（shell / 读写等）
     * 不得进入本名单，否则助手级白名单（只减不增）会被静默绕过。
     */
    val ALWAYS_KEEP_TOOL_NAMES: Set<String> =
        setOf("list_tools", "get_tool_schema", "ask_user")

    /**
     * 档位判定**含来源**：判据唯一，避免“诊断结果”与“实际装配”两套逻辑漂移。
     * 判定顺序即优先级：助手级降温 > 场景上调 > 策略热档 > 场景下调 > 策略冷档单件 > 策略冷档家族前缀 > 默认温档。
     *
     * 场景规则：
     * - `UNKNOWN` 场景 = 纯静态判定（与不传场景完全一致）。
     * - 场景上调只把静态非 HOT 的工具升为 HOT；已是 HOT 的保持原来源（避免来源失真）。
     * - 场景下调只把静态 HOT 的工具降为 WARM；静态 COLD 的工具不受场景影响
     *   （冷档拦截是治理红线，场景无权解冻——需要时走 get_tool_schema 按会话解锁）。
     * - 用户显式配置（extraCold）优先级最高，不受场景影响。
     */
    fun decide(
        toolName: String,
        extraCold: Set<String> = emptySet(),
        scene: UiScene = UiScene.UNKNOWN,
    ): TierDecision {
        // 助手级下调优先：只会把非冷档降为冷档，不会升档（见助手级 extraColdTools 配置）
        if (toolName in extraCold) return TierDecision(SurfaceTier.COLD, TierSource.ASSISTANT_EXTRA_COLD)

        val sceneHot = scene != UiScene.UNKNOWN && toolName in (SCENE_HOT[scene] ?: emptySet())
        val sceneWarm = scene != UiScene.UNKNOWN && toolName in (SCENE_WARM[scene] ?: emptySet())

        return when {
            // 场景上调：静态非 HOT → HOT（给完整 schema，省一次 get_tool_schema 往返）
            sceneHot && toolName !in HOT -> TierDecision(SurfaceTier.HOT, TierSource.SCENE_RAISED)
            toolName in HOT && !sceneWarm -> TierDecision(SurfaceTier.HOT, TierSource.POLICY_HOT)
            // 场景下调：静态 HOT → WARM（描述收敛，参数表完整保留，可直接调用）
            sceneWarm && toolName in HOT -> TierDecision(SurfaceTier.WARM, TierSource.SCENE_LOWERED)
            toolName in COLD_EXTRAS -> TierDecision(SurfaceTier.COLD, TierSource.POLICY_COLD_EXTRA)
            COLD_PREFIXES.any { toolName.startsWith(it) } ->
                TierDecision(SurfaceTier.COLD, TierSource.POLICY_COLD_PREFIX)
            else -> TierDecision(SurfaceTier.WARM, TierSource.DEFAULT_WARM)
        }
    }

    fun tierOf(
        toolName: String,
        extraCold: Set<String> = emptySet(),
        scene: UiScene = UiScene.UNKNOWN,
    ): SurfaceTier = decide(toolName, extraCold, scene).tier

    /**
     * 带**助手级白名单**的档位判定（把白名单当"中间档"用）。
     *
     * 语义：`scope` 非空时，名单内工具照常判档，**名单外一律冷档** —— 与"硬过滤"的区别在于
     * 工具**仍在注入列表里**（`list_tools` 列得到、`get_tool_schema` 查得到），只是默认只发一行
     * 用途 + 空 schema，需要时当会话解锁。保命工具不受名单影响。
     *
     * ⚠ 调用方自行保证**仅在全局裁剪开启时**使用本函数；关闭裁剪时应退回硬过滤，保持既有行为。
     */
    fun tierWithScope(
        toolName: String,
        scope: List<String>,
        extraCold: Set<String> = emptySet(),
        scene: UiScene = UiScene.UNKNOWN,
    ): TierDecision =
        when {
            scope.isEmpty() -> decide(toolName, extraCold, scene)
            toolName in scope -> decide(toolName, extraCold, scene)
            toolName in ALWAYS_KEEP_TOOL_NAMES -> decide(toolName, extraCold, scene)
            else -> TierDecision(SurfaceTier.COLD, TierSource.OUTSIDE_ASSISTANT_SCOPE)
        }

    fun tierOfWithScope(
        toolName: String,
        scope: List<String>,
        extraCold: Set<String> = emptySet(),
        scene: UiScene = UiScene.UNKNOWN,
    ): SurfaceTier = tierWithScope(toolName, scope, extraCold, scene).tier
}
