package me.rerere.rikkahub.data.workspace

import me.rerere.workspace.WorkspaceShellBackend

/**
 * 工作区可选挂载（/sdcard）与本地 Shell 后端（PROOT / ROOT_CHROOT / DISABLED）的内存开关桥。
 *
 * 设置项经 UI 写入 datastore 时同步到这里；WorkspaceManager 的挂载表与后端分派每次启动 shell、
 * 解析路径时都会同步读它，开关变化即时生效。
 * 冷启动时默认 backend = PROOT（零行为变化）；保留 [chrootEnabled] 作派生属性以便旧调用点零改动。
 */
class WorkspaceMountSwitch(
    /**
     * 持久化后端读取器：显式设置缺省时用它兜底。
     *
     * 存在的理由：本类是内存桥，冷启动时没有任何人写过它，旧实现因此恒定返回 PROOT ——
     * 即使设置项里明明是 ROOT_CHROOT，每条命令也会静默走 proot。唯一的回灌点是工作区详情页
     * 的 ViewModel（`shellBackendFlow().onEach { mountSwitch.backend = it }`），用户没进那个
     * 页面之前开关形同虚设（表现为界面显示已开启，实际进程身份未变）。
     * 设置项本身是 warm 载入的 StateFlow，可以同步读，不必等订阅。
     */
    private val persistedBackend: () -> WorkspaceShellBackend = { WorkspaceShellBackend.PROOT },
) {
    @Volatile
    var sdcardEnabled: Boolean = false

    /** 显式设置过的后端；null = 还没人写过，回落到 [persistedBackend]。 */
    @Volatile
    private var explicitBackend: WorkspaceShellBackend? = null

    /** 本地工作区 Shell 后端（三态：PROOT / ROOT_CHROOT / DISABLED）。 */
    var backend: WorkspaceShellBackend
        get() = explicitBackend ?: persistedBackend()
        set(value) {
            explicitBackend = value
        }

    /** 兼容派生属性：旧调用点读写 chrootEnabled 均自动映射到 [backend]。 */
    var chrootEnabled: Boolean
        get() = backend == WorkspaceShellBackend.ROOT_CHROOT
        set(value) {
            backend = if (value) WorkspaceShellBackend.ROOT_CHROOT else WorkspaceShellBackend.PROOT
        }
}
