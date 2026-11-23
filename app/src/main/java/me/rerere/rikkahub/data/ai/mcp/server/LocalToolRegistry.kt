package me.rerere.rikkahub.data.ai.mcp.server

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.log.AppLog

/**
 * MCP 工具注册表：按名称索引 [LocalTools] 聚合的设备工具。
 *
 * 工具集在 server 启动时同步一次；运行中改工具集时 [sync] 可再次调用，
 * server 每次 tools/list 与 tools/call 都实时读本表，无需重启服务。
 * 个别工具工厂依赖缺失时整体构建失败会自动退化为核心子集，
 * 保证 tools/list 至少返回可用工具。
 */
class LocalToolRegistry(private val localTools: LocalTools) {

    private companion object {
        const val TAG = "LocalToolRegistry"

        /** 工厂最稳定、无外部依赖的最低子集（兜底用）。 */
        val FALLBACK_CORE = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.DeviceInfo,
            LocalToolOption.Toast,
            LocalToolOption.Files,
        )
    }

    @Volatile
    private var tools: Map<String, Tool> = emptyMap()

    /** 上次同步实际采用的选项集（构建失败退化时为核心子集）。 */
    @Volatile
    var syncedOptions: List<LocalToolOption> = emptyList()
        private set

    fun sync(options: List<LocalToolOption>) {
        val first = runCatching { localTools.getTools(options) }
        val used: List<Tool>
        val effective: List<LocalToolOption>
        if (first.isSuccess) {
            used = first.getOrDefault(emptyList())
            effective = options
        } else {
            AppLog.w(TAG, "full tool build failed (${first.exceptionOrNull()?.message}); falling back to core subset")
            used = runCatching { localTools.getTools(FALLBACK_CORE) }.getOrDefault(emptyList())
            effective = FALLBACK_CORE
        }
        tools = used.associateBy { it.name }
        syncedOptions = effective
        AppLog.i(TAG, "registry synced: ${tools.size} tools")
    }

    fun all(): Collection<Tool> = tools.values

    fun get(name: String): Tool? = tools[name]

    fun size(): Int = tools.size
}