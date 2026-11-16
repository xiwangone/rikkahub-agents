package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/** 每 1M token 的 token 数（价格按 M 计）。 */
private const val TOKENS_PER_MILLION = 1_000_000.0

/** 峰时窗口默认值：白天 9:00–21:00（本地时间）。 */
private const val DEFAULT_PEAK_START_HOUR = 9
private const val DEFAULT_PEAK_END_HOUR = 21

/** 用量聚合 key（"$providerKey/$modelId"）的缺失 provider 占位。 */
private const val UNKNOWN_PROVIDER_KEY = "unknown"

/** 单个模型的自定义价格（每 1M token，自定义货币单位）。 */
@Serializable
data class ModelPrice(
    /** 基准价（每 1M token）。 */
    val pricePerMillion: Double = 0.0,
    /** 峰时价（每 1M token）；null = 峰时也用基准价。 */
    val peakPricePerMillion: Double? = null,
    /** 谷时价（每 1M token）；null = 谷时也用基准价。 */
    val offPeakPricePerMillion: Double? = null,
)

/** 单个提供商的额度/用量（自定义货币单位；null = 未填，仅展示用，不参与费用计算）。 */
@Serializable
data class ProviderPricing(
    /** 额度（如 API 余额）；null = 未填。 */
    val quota: Double? = null,
    /** 用量（用户手填的已用金额）；null = 未填。 */
    val manualUsage: Double? = null,
)

/**
 * 用量统计的自定义价格设置。
 *
 * 口径：自填价只用于**估算** provider 未上报费用的生成；上报过的费用仍按上报值计，
 * 两者不混淆。货币单位由用户自填价决定（符号见 [currencySymbol]）。
 */
@Serializable
data class UsagePricingSettings(
    /** 模型价格：key = [usagePricingKey]，与用量聚合 key 一致。 */
    val modelPrices: Map<String, ModelPrice> = emptyMap(),
    /** 提供商额度/用量：key = providerKey。 */
    val providerPricing: Map<String, ProviderPricing> = emptyMap(),
    /** 峰谷价总开关（关 = 全天用基准价）。 */
    val peakEnabled: Boolean = false,
    /** 峰时窗口开始（本地时间，小时 0-23，含 start）。 */
    val peakStartHour: Int = DEFAULT_PEAK_START_HOUR,
    /** 峰时窗口结束（本地时间，小时 0-23，不含 end；与 start 相同 = 全天峰时）。 */
    val peakEndHour: Int = DEFAULT_PEAK_END_HOUR,
    /** 货币符号（默认 $），仅展示用。 */
    val currencySymbol: String = "$",
    /** 积分名称（空 = 不显示积分）。 */
    val pointsName: String = "",
    /** 每 1 单位货币兑换的积分数（<= 0 = 不换算）。 */
    val pointsPerCurrency: Double = 0.0,
) {
    /** 展示用的货币符号（未填时回退到 $）。 */
    val effectiveCurrencySymbol: String
        get() = currencySymbol.ifBlank { "$" }

    /** 该时刻是否落在峰时窗口内（设备本地时区）。 */
    fun isPeak(ts: Long): Boolean {
        if (!peakEnabled) return false
        val hour = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).hour
        val start = peakStartHour.coerceIn(0, 23)
        val end = peakEndHour.coerceIn(0, 23)
        if (start == end) return true
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    /**
     * 按自填价估算一次生成的费用（仅 provider 未上报费用时调用）。
     *
     * @return 估算费用；该模型没有自填价时返回 null（仍显示"未上报"）。
     */
    fun estimateCost(
        providerKey: String?,
        modelId: String,
        promptTokens: Long,
        completionTokens: Long,
        ts: Long,
    ): Double? {
        val price = modelPrices[usagePricingKey(providerKey, modelId)] ?: return null
        val totalTokens = (promptTokens + completionTokens).coerceAtLeast(0)
        val perMillion =
            when {
                // 峰谷开关关着时全天都按基准价；不能落到谷价分支
                !peakEnabled -> price.pricePerMillion
                isPeak(ts) -> price.peakPricePerMillion ?: price.pricePerMillion
                else -> price.offPeakPricePerMillion ?: price.pricePerMillion
            }
        return totalTokens / TOKENS_PER_MILLION * perMillion.coerceAtLeast(0.0)
    }

    /** 把费用换算成积分；未设置积分时返回 null。 */
    fun toPoints(cost: Double): Double? {
        if (pointsName.isBlank() || pointsPerCurrency <= 0.0) return null
        return cost * pointsPerCurrency
    }
}

/** 模型价格的 key（"$providerKey/$modelId"），与用量聚合 key 口径一致。 */
fun usagePricingKey(providerKey: String?, modelId: String): String =
    "${providerKey ?: UNKNOWN_PROVIDER_KEY}/$modelId"

/**
 * 默认展示的模型行：已有自填价的 + 统计里出现过的 + 本页新加的。
 * 不枚举全部提供商的全部模型——每行都是带输入框的卡片，模型一多页面就卡。
 */
fun visibleModelKeys(
    pricedKeys: Set<String>,
    statsKeys: Set<String>,
    addedKeys: Set<String>,
): List<String> = (pricedKeys + statsKeys + addedKeys).distinct().sorted()

/**
 * 默认展示的提供商行：已有额度/用量配置的 + 统计里出现过的 + 已展示模型的提供商。
 * 同样不枚举全部提供商。
 */
fun visibleProviderKeys(
    pricedKeys: Set<String>,
    statsKeys: Set<String>,
    modelKeys: Collection<String>,
): List<String> =
    (pricedKeys + statsKeys.map { it.substringBefore("/") } + modelKeys.map { it.substringBefore("/") })
        .distinct()
        .sorted()
