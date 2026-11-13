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
 * 所以这里取两步：**可选参数优先，缺省是第一个本地工作区**（缺省若是"列表第一个"、
 * 第一个恰好是 SSH 工作区，明文会直接落到远端；没有本地工作区时报错、要求显式
 * 指定）；显式指向 SSH/远程工作区时，调用点还要过 [remoteTargetNeedsConfirm] 的二
 * 次确认（`confirm=true`），缺省不放行。真正的落点（工作区名 + id）回给调用方，
 * 让它自己判断要不要换地方。
 */
internal data class VaultWorkspaceTarget(
    val id: String,
    val name: String,
    val isFallback: Boolean,
    /** 目标是否为 SSH/远程工作区：是则调用点必须要求 confirm=true 才可用。 */
    val isSsh: Boolean,
)

/**
 * @param requested 用户/调用方显式指定的工作区（名字或 id）；null / 空白 = 用第一个
 * @return 成功时给出目标工作区；失败时 message 里带上可选工作区名，便于调用方纠正参数
 */
internal suspend fun resolveVaultWorkspace(
    wsRepository: WorkspaceRepository,
    requested: String?,
): Result<VaultWorkspaceTarget> {
    val all = wsRepository.getAll()
    if (all.isEmpty()) return Result.failure(IllegalStateException("No workspace"))
    val want = requested?.trim()?.takeIf { it.isNotEmpty() }
    if (want == null) {
        // 缺省只允许本地工作区：列表第一个若是 SSH 工作区，明文导出会直接落到远端。
        // 没有本地工作区时报错、要求显式指定目标（+ confirm=true 二次确认），不静默回退到远程。
        val ws = all.firstOrNull { !it.isSsh() }
            ?: return Result.failure(
                IllegalStateException(
                    "No local workspace: refusing to write plaintext credentials to a remote workspace by default. " +
                        "Pass workspace=<ssh workspace name|id> with confirm=true to target it explicitly.",
                ),
            )
        return Result.success(VaultWorkspaceTarget(ws.id, ws.name, isFallback = true, isSsh = false))
    }
    val hit = all.firstOrNull { it.id == want || it.name.equals(want, ignoreCase = true) }
        ?: return Result.failure(
            IllegalStateException(
                "Workspace not found: $want. Available: " + all.joinToString(", ") { it.name },
            ),
        )
    return Result.success(VaultWorkspaceTarget(hit.id, hit.name, isFallback = false, isSsh = hit.isSsh()))
}

/**
 * 远程目标二次确认：显式指向 SSH/远程工作区时，调用方必须传 `confirm=true`
 * 明确确认落点，否则拒绝。返回失败原因；null = 可用。
 *
 * 做成独立函数而不是放进 [resolveVaultWorkspace]：解析只负责"找到目标"，确认是
 * 工具参数层面的策略（`confirm` 参数只在调用点才有），两者分开、调用点自行决定
 * 拒绝时的报错文案与返回路径。
 */
internal fun remoteTargetNeedsConfirm(target: VaultWorkspaceTarget, confirm: Boolean): String? =
    if (target.isSsh && !confirm) {
        "Refusing to use remote workspace '${target.name}' for plaintext credentials without confirmation: " +
            "pass confirm=true to confirm the destination explicitly."
    } else {
        null
    }

/** 统一的结果尾巴：告诉调用方文件真正落在哪个工作区、该用哪条命令清理。 */
internal fun vaultTargetNote(target: VaultWorkspaceTarget, virtualPath: String): String {
    val fallback =
        if (target.isFallback) {
            " [default: first local workspace; pass workspace=<name|id> to target another]"
        } else {
            ""
        }
    return "workspace=${target.name} (id=${target.id})$fallback; actual path in that workspace: $virtualPath"
}
