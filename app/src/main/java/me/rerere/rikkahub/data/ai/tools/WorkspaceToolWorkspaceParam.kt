package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.repository.WorkspaceRepository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 可选参数 `workspace`：本次调用要操作的目标工作区 id（取自 `workspace_list`）。
 *
 * 省略时使用助手绑定的工作区；传了未知 id 会报错（**不静默回退**），避免"以为操作 A、
 * 实际落在 B"这类静默失败 —— 2026-09-24 实测踩过：该参数此前未在 schema 中声明，
 * 传入即被 JSON 丢弃，所有跨区调用静默作用到当前绑定区。
 */
internal fun JsonObjectBuilder.putWorkspaceProperty() {
    put("workspace", buildJsonObject {
        put("type", "string")
        put(
            "description",
            "Optional target workspace id (from workspace_list). Defaults to the assistant's bound workspace; an unknown id is an error.",
        )
    })
}

/**
 * 解析本次调用的目标工作区 id。
 *
 * - 未传或与绑定区相同 → 返回 [boundId]（零开销，不查库）
 * - 传了其他 id → 必须是**已存在**的工作区，否则抛错（**不静默回退**）
 */
internal suspend fun resolveTargetWorkspaceId(
    workspaceRepository: WorkspaceRepository,
    args: JsonObject,
    boundId: String,
): String {
    val requested = args["workspace"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (requested.isEmpty() || requested == boundId) return boundId
    if (workspaceRepository.getById(requested) == null) {
        error("unknown workspace id: $requested (use workspace_list to see available ids)")
    }
    return requested
}

