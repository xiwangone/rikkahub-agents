package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceShellBackend

/**
 * 工作区可选挂载（/sdcard）与本地 Shell 后端（PROOT / ROOT_CHROOT / DISABLED）的内存开关桥。
 *
 * 设置项经 UI 写入 datastore 时同步到这里；WorkspaceManager 的挂载表与后端分派每次启动 shell、
 * 解析路径时都会同步读它，开关变化即时生效。
 * 冷启动时默认 backend = PROOT（零行为变化）；保留 [chrootEnabled] 作派生属性以便旧调用点零改动。
 */
class WorkspaceMountSwitch {
    @Volatile
    var sdcardEnabled: Boolean = false

    /** 本地工作区 Shell 后端（三态：PROOT / ROOT_CHROOT / DISABLED），默认 PROOT。 */
    @Volatile
    var backend: WorkspaceShellBackend = WorkspaceShellBackend.PROOT

    /** 兼容派生属性：旧调用点读写 chrootEnabled 均自动映射到 [backend]。 */
    var chrootEnabled: Boolean
        get() = backend == WorkspaceShellBackend.ROOT_CHROOT
        set(value) {
            backend = if (value) WorkspaceShellBackend.ROOT_CHROOT else WorkspaceShellBackend.PROOT
        }
}
