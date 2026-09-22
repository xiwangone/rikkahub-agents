package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    @SerialName("off")
    OFF(0, "none"),

    @SerialName("auto")
    AUTO(-1, "auto"),

    @SerialName("low")
    LOW(1_000, "low"),

    @SerialName("medium")
    MEDIUM(2_000, "medium"),

    @SerialName("high")
    HIGH(8_000, "high"),

    @SerialName("xhigh")
    XHIGH(16_000, "xhigh"),

    @SerialName("max")
    MAX(32_000, "max");

    val isEnabled: Boolean
        get() = this != OFF

    /**
     * 是否应把档位作为 `effort` / `reasoning_effort` 发给 provider。
     *
     * 排除两类：**OFF**（值 `"none"`）与 **AUTO**（值 `"auto"`）—— 二者都不在多数 provider
     * 接受的枚举里（如 `low|medium|high|xhigh|max`）。曾经只排除 AUTO，导致 OFF 档发出
     * `"none"` 被 provider 以 400 拒绝（2026-09-22 实测：同一模型、同一配置，provider 侧开始校验）。
     */
    val shouldSendEffort: Boolean
        get() = isEnabled && this != AUTO

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            return entries.minByOrNull {
                kotlin.math.abs(
                    it.budgetTokens - (budgetTokens ?: AUTO.budgetTokens)
                )
            } ?: AUTO
        }
    }
}
