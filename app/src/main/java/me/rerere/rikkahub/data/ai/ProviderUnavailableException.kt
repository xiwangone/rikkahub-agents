package me.rerere.rikkahub.data.ai

/**
 * 本地配置层面的「模型找不到供应商」。
 *
 * 与「服务端返回 model not found」不同：它不是 API 报错，而是本地数据状态问题 —— 升级/导入/删供应商之后，
 * 当前模型引用的 `modelId` 与任何 provider 的 `models` 列表都对不上，于是生成在解析阶段就失败。
 *
 * 单独建模的原因：这类失败**用户自己就能修**（重新选一个模型），所以上报时要带明确的行动指引，
 * 而不是把英文原文 "Provider not found" 丢给用户（见 issue #15）。
 *
 * 归类上等同于 [FailureKind.MODEL_NOT_FOUND]（由调用方判定，避免抬高分类器的圈复杂度）。
 */
class ProviderUnavailableException(
    val modelId: String,
    val providerHint: String? = null,
) : IllegalStateException(
        buildString {
            append("The current model is no longer available: its provider was deleted or renamed, please select another model")
            append("（model=")
            append(modelId)
            if (!providerHint.isNullOrBlank()) append(", backend=").append(providerHint)
            append("）")
        },
    )
