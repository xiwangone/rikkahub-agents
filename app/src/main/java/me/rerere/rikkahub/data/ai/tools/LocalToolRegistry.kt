package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * 工具注册表：LocalToolOption 到工具构建逻辑的映射。
 *
 * 从 LocalTools.getTools 抽出——原函数 428 行、圈复杂度 64，
 * 本质是 50+ 个 `if (enabled(X)) { tools.add(...) }` 的注册表。
 * 改为数据驱动后，getTools 只剩循环 + 授权门判断。
 *
 * 构建 lambda 以 LocalTools 为 receiver（取 context 与各类依赖），
 * 以 tools 列表为参数（部分工具的 knownToolNamesProvider 闭包需要它）。
 */
internal data class ToolEntry(
    val option: LocalToolOption,
    val addTo: LocalTools.(tools: MutableList<Tool>, invocationContext: ToolInvocationContext) -> Unit,
)

