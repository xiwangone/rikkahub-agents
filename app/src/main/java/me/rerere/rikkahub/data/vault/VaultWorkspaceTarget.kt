package me.rerere.rikkahub.data.vault

import me.rerere.rikkahub.data.repository.WorkspaceRepository

/**
 * vault 文件类工具（`vault_export_env` / `vault_export_loadcreds` / `vault_import_loadcreds` /
 * `vault_compare_loadcreds`）的**目标工作区**解析。
 *
 * 为什么需要：这几个工具原先一律 `wsRepository.getAll().firstOrNull()` —— 写入与读取都锁在
 * 「工作区列表里的第一个」，而返回给调用方的却是虚拟路径 `/workspace/tmp/…`。
 * 调用方在自己的工作区 shell 里因此既 source 不到、也删不掉那个明文凭证文件，
 * 明文会滞留在非预期的工作区里。
 *
 * 工具执行上下文拿不到"调用方是哪个工作区"（`Tool.execute` 闭包里没有 workspace 信息），
 * 所以这里取两步：**可选参数优先，缺省仍是第一个**（保持旧行为不变），
 * 但把真正的落点（工作区名 + id）回给调用方，让它自己判断要不要换地方。
 */
internal data class VaultWorkspaceTarget(
    val id: String,
    val name: String,
    val isFallback: Boolean,
)

/**
 * @param requested 用户/调用方显式指定的工作区（名字或 id）；null / 空白 = 用第一个
 * @return 成功时给出目标工作区；失败时 message 里带上可选工作区名，便于调用方纠正参数
 */
internal fun resolveVaultWorkspace(
    wsRepository: WorkspaceRepository,
    requested: String?,
): Result<VaultWorkspaceTarget> {
    val all = wsRepository.getAll()
    if (all.isEmpty()) return Result.failure(IllegalStateException("No workspace"))
    val want = requested?.trim()?.takeIf { it.isNotEmpty() }
    if (want == null) {
        val ws = all.first()
        return Result.success(VaultWorkspaceTarget(ws.id, ws.name, isFallback = true))
    }
    val hit = all.firstOrNull { it.id == want || it.name.equals(want, ignoreCase = true) }
        ?: return Result.failure(
            IllegalStateException(
                "Workspace not found: $want. Available: " + all.joinToString(", ") { it.name },
            ),
        )
    return Result.success(VaultWorkspaceTarget(hit.id, hit.name, isFallback = false))
}

/** 统一的结果尾巴：告诉调用方文件真正落在哪个工作区、该用哪条命令清理。 */
internal fun vaultTargetNote(target: VaultWorkspaceTarget, virtualPath: String): String {
    val fallback =
        if (target.isFallback) {
            " [default: first workspace; pass workspace=<name|id> to target another]"
        } else {
            ""
        }
    return "workspace=${target.name} (id=${target.id})$fallback; actual path in that workspace: $virtualPath"
}
