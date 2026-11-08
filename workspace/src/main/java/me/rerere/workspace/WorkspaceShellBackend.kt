package me.rerere.workspace

/**
 * 本地工作区 Shell 执行后端（三态）：
 * - [PROOT]：默认，用户态模拟 root（无需设备 root）
 * - [ROOT_CHROOT]：真 root chroot（`su -c chroot-run.sh`，私有 mount ns 挂载自毁）
 * - [DISABLED]：关闭本地 Shell 后端
 */
enum class WorkspaceShellBackend {
    PROOT,
    ROOT_CHROOT,
    DISABLED,
    ;

    companion object {
        /**
         * 幂等解析与旧值迁移：
         * - 显式传入合法枚举名（PROOT / ROOT_CHROOT / DISABLED）→ 对应枚举
         * - 缺失 / 空串 / 未知值 → 按 [legacyChrootEnabled] 映射（true → ROOT_CHROOT，false/null → PROOT）
         */
        fun fromPreference(
            rawBackend: String?,
            legacyChrootEnabled: Boolean? = null,
        ): WorkspaceShellBackend {
            val normalized = rawBackend?.trim()?.uppercase()
            if (!normalized.isNullOrEmpty()) {
                entries.firstOrNull { it.name == normalized }?.let { return it }
            }
            return if (legacyChrootEnabled == true) ROOT_CHROOT else PROOT
        }
    }
}
