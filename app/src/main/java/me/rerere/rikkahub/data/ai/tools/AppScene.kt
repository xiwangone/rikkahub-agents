package me.rerere.rikkahub.data.ai.tools

/**
 * 当前 UI 场景信号：由 UI 层（RouteActivity 观测导航栈）写入，
 * 数据层（ChatService 在装配工具时）读取。
 *
 * 设计为简单的 `@Volatile` 变量而非 Flow/StateFlow：工具装配是按需触发的
 * （每次 generation 调用一次 `ChatToolFactory.createTools`），“装配时读一次
 * 当前值”已足够实时，无需响应式订阅，也避免数据层依赖 UI 层的可观察状态。
 *
 * 线程安全：读写均为单变量 volatile 操作；UI 线程写入、后台线程读取。
 */
object AppScene {
    @Volatile
    var current: UiScene = UiScene.UNKNOWN
        private set

    fun update(scene: UiScene) {
        current = scene
    }
}
