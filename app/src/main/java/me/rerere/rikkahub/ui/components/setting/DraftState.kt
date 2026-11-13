package me.rerere.rikkahub.ui.components.setting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 草稿 + 取消回滚共享状态机：持有「已保存值」与「编辑中草稿」两份状态。
 *
 * 典型用法（设置弹窗）：打开弹窗时 `load(已保存值)`，字段编辑走 `edit { it.copy(...) }`，
 * 保存时 `save()` 拿返回值写库，取消时 `discard()` 丢弃草稿——各页原来手写的
 * `loadXxxDrafts()` / `saveXxx()` / 取消重载三件套不再每页重复。
 * `T` 应为不可变数据（data class / 不可变 List），`isDirty` 靠结构相等判定。
 *
 * `saved` / `draft` 用 snapshot state 持有：编辑必须触发重组，纯 var 持有者做不到；
 * JVM 单测仍可直接读写。
 */
class DraftState<T>(initialSaved: T) {
    var saved: T by mutableStateOf(initialSaved)
        private set

    var draft: T by mutableStateOf(initialSaved)
        private set

    /** 草稿与已保存值不一致即脏（取消时可丢弃、保存时可写库）。 */
    val isDirty: Boolean get() = draft != saved

    /** 从外部已保存值重载（设置流更新 / 弹窗打开时调用）：草稿与已保存值同步。 */
    fun load(saved: T) {
        this.saved = saved
        draft = saved
    }

    /** 替换整份草稿。 */
    fun edit(value: T) {
        draft = value
    }

    /** 按变换替换草稿（字段编辑时常用 `edit { it.copy(xxx = newValue) }`）。 */
    fun edit(transform: (T) -> T) {
        draft = transform(draft)
    }

    /** 丢弃草稿，回到已保存值。 */
    fun discard() {
        draft = saved
    }

    /**
     * 提交草稿为已保存值并返回它（页面拿返回值写库）。
     * 写库后设置流回推新值，页面再 `load()` 同步一次，无害。
     */
    fun save(): T {
        saved = draft
        return saved
    }
}
