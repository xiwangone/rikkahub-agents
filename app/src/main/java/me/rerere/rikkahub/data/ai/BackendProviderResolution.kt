package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.findProvider

/** 按 executionBackend 解析执行 provider + 模型：local/空→模型自动；否则→指定 provider(取该 provider 默认模型)。 */
internal fun resolveBackendProvider(executionBackend: String, model: Model, providers: List<ProviderSetting>): Pair<ProviderSetting, Model>? =
    if (executionBackend.isBlank() || executionBackend == "local") {
        model.findProvider(providers)?.let { it to model }
    } else {
        providers.firstOrNull { it.id.toString() == executionBackend }?.let { p -> p to (p.models.firstOrNull() ?: model) }
    }
