package me.rerere.rikkahub.ui.components.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 确认弹窗共享状态机：只持有「待确认的目标」，不渲染对话框。
 *
 * 渲染层仍用已有的 `RikkaConfirmDialog` / `RikkaDeleteConfirmDialog`（纯展示层组件），
 * 页面用 `remember` 持有本类实例，确认/取消后由页面执行副作用。
 * 各页原来散落的 `pendingDelete: T?` / `pendingRevoke: String?` / `pendingXxx: Boolean`
 * 一律改调本类，确认/取消的置空逻辑不再每页手写一遍。
 *
 * `pending` 用 snapshot state 持有：`request()` / `confirm()` / `dismiss()` 必须触发重组
 * 对话框才弹得出、才关得掉，纯 var 持有者做不到这一点；JVM 单测仍可直接读写。
 */
class ConfirmDialogState<T> {
    var pending: T? by mutableStateOf(null)
        private set

    val isShowing: Boolean get() = pending != null

    fun request(target: T) {
        pending = target
    }

    /** 取出待确认目标并清空；未在确认中时返回 null。 */
    fun confirm(): T? {
        val target = pending
        pending = null
        return target
    }

    fun dismiss() {
        pending = null
    }
}

/** 无载荷的布尔型确认（`pendingDelete: Boolean` 一类）：`request()` 不带参数。 */
fun ConfirmDialogState<Unit>.request() = request(Unit)
