package me.rerere.rikkahub.data.ai.mcp.server

import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools

/**
 * MCP 工具注册表：按名称索引 [LocalTools] 聚合的设备工具。
 *
 * 工具集在 server 启动时同步一次（静态）；个别工具工厂依赖缺失时整体构建失败会
 * 自动退化为核心子集，保证 tools/list 至少返回可用工具。
 */
class LocalToolRegistry(private val localTools: LocalTools) {

    private companion object {
        const val TAG = "LocalToolRegistry"

        /** 工厂最稳定、无外部依赖的最低子集（兜底用）。 */
        val FALLBACK_CORE = listOf(
            LocalToolOption.TimeInfo,
            LocalToolOption.Clipboard,
            LocalToolOption.Battery,
            LocalToolOption.AudioInfo,
            LocalToolOption.TelephonyInfo,
            LocalToolOption.WifiInfo,
            LocalToolOption.Sensors,
            LocalToolOption.StorageInfo,
            LocalToolOption.Toast,
            LocalToolOption.Files,
        )
    }

    private var tools: Map<String, Tool> = emptyMap()

    fun sync(options: List<LocalToolOption>) {
        tools = runCatching { localTools.getTools(options) }.getOrElse { e ->
            Log.w(TAG, "full tool build failed (${e.message}); falling back to core subset")
            runCatching { localTools.getTools(FALLBACK_CORE) }.getOrDefault(emptyList())
        }.associateBy { it.name }
        Log.i(TAG, "registry synced: ${tools.size} tools")
    }

    fun all(): Collection<Tool> = tools.values

    fun get(name: String): Tool? = tools[name]

    fun size(): Int = tools.size
}