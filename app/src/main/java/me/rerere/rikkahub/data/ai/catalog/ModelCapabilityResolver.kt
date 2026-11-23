package me.rerere.rikkahub.data.ai.catalog

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry

/** 能力字段的生效来源。 */
enum class CapabilitySource {
    /** 本机表（`files/model-capabilities.json`）的覆盖条目。 */
    LOCAL,

    /** 基线表（`assets/model-capabilities.json`）的覆盖条目。 */
    BASELINE,

    /** 内置登记表 ∪ 公开目录 ∪ models.dev 的并集。 */
    MERGED,

    /** 无任何来源提供时的回退值（纯文本 / 无能力 / 未知上下文）。 */
    DEFAULT,
}

/** 一个模型解析后的全部能力，逐字段标明来源。 */
data class ResolvedCapabilities(
    val modelId: String,
    val input: Set<Modality>,
    val inputSource: CapabilitySource,
    val output: Set<Modality>,
    val outputSource: CapabilitySource,
    val abilities: Set<ModelAbility>,
    val abilitiesSource: CapabilitySource,
    val contextLength: Int?,
    val contextLengthSource: CapabilitySource,
)

/**
 * 模型能力解析：把“外置覆盖表 → 内置 ∪ 目录 → 默认”的解析口径按字段拆成
 * “生效值 + 来源”，供能力编辑器展示。
 *
 * 覆盖语义与 [ModelCapabilityOverrides] 的查询一致：首条命中的条目（本机表在前）
 * 其非空字段直接采用；该条目某字段为空时回落到并集，而不是去查基线表同字段。
 * 并集层与默认值的区分：输入/输出只有纯文本、能力为空、上下文为空时记为
 * [CapabilitySource.DEFAULT]——登记表公开 API 无法区分“登记为纯文本”与
 * “无任何登记时的回退纯文本”，此处按回退值处理。
 */
object ModelCapabilityResolver {

    fun resolve(modelId: String, overrides: ModelCapabilityOverrides): ResolvedCapabilities {
        val matched = matchingEntry(modelId, overrides)
        val entry = matched?.first
        // entry 与 entrySource 成对出现：matchingEntry 要么返回 null，要么返回两者皆非空的 Pair。
        // entry 非空时 entrySource 必然非空；entry 为空时这里取 DEFAULT，但各分支都走回落路径，source 不会被使用。
        val source = matched?.second ?: CapabilitySource.DEFAULT

        val (input, inputSource) = if (entry?.input != null) {
            entry.input to source
        } else {
            val merged = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId).toSet()
            merged to if (merged == setOf(Modality.TEXT)) CapabilitySource.DEFAULT else CapabilitySource.MERGED
        }
        val (output, outputSource) = if (entry?.output != null) {
            entry.output to source
        } else {
            val merged = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId).toSet()
            merged to if (merged == setOf(Modality.TEXT)) CapabilitySource.DEFAULT else CapabilitySource.MERGED
        }
        val (abilities, abilitiesSource) = if (entry?.abilities != null) {
            entry.abilities to source
        } else {
            val merged = ModelRegistry.MODEL_ABILITIES.getData(modelId).toSet()
            merged to if (merged.isEmpty()) CapabilitySource.DEFAULT else CapabilitySource.MERGED
        }
        val (contextLength, contextLengthSource) = if (entry?.contextLength != null) {
            entry.contextLength to source
        } else {
            val merged = ModelRegistry.MODEL_CONTEXT_LENGTH.getData(modelId)
            merged to if (merged == null) CapabilitySource.DEFAULT else CapabilitySource.MERGED
        }
        return ResolvedCapabilities(
            modelId = modelId,
            input = input,
            inputSource = inputSource,
            output = output,
            outputSource = outputSource,
            abilities = abilities,
            abilitiesSource = abilitiesSource,
            contextLength = contextLength,
            contextLengthSource = contextLengthSource,
        )
    }

    /** 首条命中的覆盖条目及所在表（本机表在前），与 [ModelCapabilityOverrides] 的查询一致。 */
    private fun matchingEntry(
        modelId: String,
        overrides: ModelCapabilityOverrides,
    ): Pair<ModelCapabilityOverrides.Entry, CapabilitySource>? {
        val id = modelId.lowercase()
        overrides.localEntries()
            .firstOrNull { it.match.isNotEmpty() && id.contains(it.match.lowercase()) }
            ?.let { return it to CapabilitySource.LOCAL }
        overrides.baselineEntries()
            .firstOrNull { it.match.isNotEmpty() && id.contains(it.match.lowercase()) }
            ?.let { return it to CapabilitySource.BASELINE }
        return null
    }
}
