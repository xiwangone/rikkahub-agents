package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility

/**
 * 外部能力目录桥（可选）。
 *
 * 内置登记表是人工核实过的、离线可用，但**新模型必须等发版**才能被识别。这里预留一个由
 * app 侧注入的目录提供者：内置表未命中该模型时向它查询（例如来自公开模型目录、已缓存到
 * 本地的能力表），于是**无需发版**也能支持新模型。
 *
 * 解析顺序固定为：**外置覆盖表（人工可编辑）→ 内置登记表 ∪ 外部目录 → 默认（纯文本 / 无能力）**。
 * 未注入或查不到时，行为与从前完全一致，不影响离线可用性。
 */
object ModelCatalogBridge {
    interface Provider {
        fun inputModalities(modelId: String): Set<Modality>?

        fun outputModalities(modelId: String): Set<Modality>?

        fun abilities(modelId: String): Set<ModelAbility>?

        /**
         * **强覆盖层**（外置能力表）：返回非 null 时直接采用，不再与内置登记表合并。
         * 与上面的 `inputModalities` 区别：那个是"并集补充"（只能补漏标），
         * 这个用于**人工纠正错标**（如内置表误判某模型支持视觉）。默认不参与。
         */
        fun overrideInputModalities(modelId: String): Set<Modality>? = null

        fun overrideOutputModalities(modelId: String): Set<Modality>? = null

        fun overrideAbilities(modelId: String): Set<ModelAbility>? = null
    }

    @Volatile
    private var provider: Provider? = null

    /** 由 app 模块在启动时注入一次。 */
    fun install(provider: Provider) {
        this.provider = provider
    }

    internal fun inputModalities(modelId: String): Set<Modality>? = provider?.inputModalities(modelId)

    internal fun outputModalities(modelId: String): Set<Modality>? = provider?.outputModalities(modelId)

    internal fun abilities(modelId: String): Set<ModelAbility>? = provider?.abilities(modelId)

    internal fun overrideInputModalities(modelId: String): Set<Modality>? =
        provider?.overrideInputModalities(modelId)

    internal fun overrideOutputModalities(modelId: String): Set<Modality>? =
        provider?.overrideOutputModalities(modelId)

    internal fun overrideAbilities(modelId: String): Set<ModelAbility>? =
        provider?.overrideAbilities(modelId)
}
