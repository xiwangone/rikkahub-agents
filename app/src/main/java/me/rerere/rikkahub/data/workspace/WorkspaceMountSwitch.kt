package me.rerere.rikkahub.data.workspace

/**
 * 工作区可选挂载（/sdcard）的内存开关桥。
 *
 * 设置项经 UI 写入 datastore 时同步到这里；WorkspaceManager 的挂载表每次启动 shell、
 * 解析路径时都会同步读它，开关变化即时生效。
 * 冷启动时初始为 false（默认关）；设置项 flow 的订阅会把它灌回真实值 ——
 * 即 UI 未打开时短暂保持 false，行为安全（默认拒绝）。
 */
class WorkspaceMountSwitch {
    @Volatile
    var sdcardEnabled: Boolean = false
}
