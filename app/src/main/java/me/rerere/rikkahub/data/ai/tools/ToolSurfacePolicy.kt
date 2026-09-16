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
            // vault_ssh_exec 实测 79 次、排第 6 高频（xEdge 不可达时是连 PC 的主力备用通道），
            // 属高频而非低频，故留在热档：每会话首次调用都要多一次 get_tool_schema 往返。
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

    fun tierOf(
        toolName: String,
        extraCold: Set<String> = emptySet(),
    ): SurfaceTier =
        when {
            // 助手级下调优先：只会把非冷档降为冷档，不会升档（见 Assistant.extraColdTools）
            toolName in extraCold -> SurfaceTier.COLD
            toolName in HOT -> SurfaceTier.HOT
            toolName in COLD_EXTRAS -> SurfaceTier.COLD
            COLD_PREFIXES.any { toolName.startsWith(it) } -> SurfaceTier.COLD
            else -> SurfaceTier.WARM
        }
}
