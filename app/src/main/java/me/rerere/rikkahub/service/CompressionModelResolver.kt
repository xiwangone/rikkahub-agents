package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

/**
 * 解析压缩用的模型：跳过「Auto」占位（它属于内置但默认禁用的 provider）以及任何被禁用的
 * provider，依次回退 —— 配置的压缩模型 → 当前聊天模型 → 任意启用 provider 下的第一个模型。
 * 都不可用时返回 null，由调用方给出可行动的提示。纯函数，便于单测。
 */
fun resolveCompressionModel(settings: Settings): Model? {
    fun Model.takeIfUsable(): Model? {
        if (id == DEFAULT_AUTO_MODEL_ID) return null
        val provider = findProvider(settings.providers) ?: return null
        return if (provider.enabled) this else null
    }

    settings.findModelById(settings.compressModelId)?.takeIfUsable()?.let { return it }
    settings.getCurrentChatModel()?.takeIfUsable()?.let { return it }
    return settings.providers
        .filter { it.enabled }
        .flatMap { it.models }
        .firstNotNullOfOrNull { it.takeIfUsable() }
}
