package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 自填价估算（纯函数）：① 没价不估算（null）；② 按 (输入 + 输出) / 1M × 单价；
 * ③ 峰谷窗口按设备本地时间判定；④ 积分换算只在名称与汇率都设置时生效。
 */
class UsagePricingTest {

    private fun tsAtHour(hour: Int): Long =
        LocalDateTime.now().withHour(hour).withMinute(0).withSecond(0).withNano(0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun pricingWith(price: ModelPrice, peakEnabled: Boolean = false): UsagePricingSettings =
        UsagePricingSettings(
            modelPrices = mapOf("openai/gpt-4" to price),
            peakEnabled = peakEnabled,
            peakStartHour = 9,
            peakEndHour = 21,
        )

    @Test
    fun `pricing key joins provider and model ids`() {
        assertEquals("openai/gpt-4", usagePricingKey("openai", "gpt-4"))
        assertEquals("unknown/m1", usagePricingKey(null, "m1"))
    }

    @Test
    fun `no price means no estimate`() {
        val pricing = UsagePricingSettings()
        assertNull(
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 500_000, tsAtHour(10)),
        )
    }

    @Test
    fun `base price applies to prompt plus completion tokens`() {
        val pricing = pricingWith(ModelPrice(pricePerMillion = 2.0))
        assertEquals(
            3.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 500_000, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
        assertEquals(
            0.0,
            pricing.estimateCost("openai", "gpt-4", 0, 0, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `zero price estimates zero instead of null`() {
        // 免费模型：有价（0）与无价（null）必须区分，否则免费模型永远显示"未上报"
        val pricing = pricingWith(ModelPrice(pricePerMillion = 0.0))
        assertEquals(
            0.0,
            pricing.estimateCost("openai", "gpt-4", 100, 100, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `negative price is clamped to zero`() {
        val pricing = pricingWith(ModelPrice(pricePerMillion = -5.0))
        assertEquals(
            0.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `peak and off-peak prices follow the local time window`() {
        val pricing = pricingWith(
            ModelPrice(pricePerMillion = 2.0, peakPricePerMillion = 10.0, offPeakPricePerMillion = 1.0),
            peakEnabled = true,
        )
        // 10:00 在 9–21 峰时窗口内
        assertEquals(
            10.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
        // 22:00 在窗口外
        assertEquals(
            1.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(22)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `missing peak price falls back to the base price`() {
        val pricing = pricingWith(
            ModelPrice(pricePerMillion = 2.0, offPeakPricePerMillion = 1.0),
            peakEnabled = true,
        )
        assertEquals(
            2.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
        val offPeakOnly = pricingWith(
            ModelPrice(pricePerMillion = 2.0, peakPricePerMillion = 10.0),
            peakEnabled = true,
        )
        assertEquals(
            2.0,
            offPeakOnly.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(22)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `peak pricing disabled means base price all day`() {
        val pricing = pricingWith(
            ModelPrice(pricePerMillion = 2.0, peakPricePerMillion = 10.0, offPeakPricePerMillion = 1.0),
            peakEnabled = false,
        )
        assertEquals(
            2.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(10)) ?: -1.0,
            1e-9,
        )
        assertEquals(
            2.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(22)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `overnight peak window wraps past midnight`() {
        val pricing = UsagePricingSettings(
            modelPrices = mapOf(
                "openai/gpt-4" to ModelPrice(pricePerMillion = 2.0, peakPricePerMillion = 10.0, offPeakPricePerMillion = 1.0),
            ),
            peakEnabled = true,
            peakStartHour = 22,
            peakEndHour = 6,
        )
        assertTrue(pricing.isPeak(tsAtHour(23)))
        assertTrue(pricing.isPeak(tsAtHour(3)))
        assertTrue("start == end means all-day peak", pricing.copy(peakStartHour = 9, peakEndHour = 9).isPeak(tsAtHour(15)))
        assertEquals(
            10.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(3)) ?: -1.0,
            1e-9,
        )
        assertEquals(
            1.0,
            pricing.estimateCost("openai", "gpt-4", 1_000_000, 0, tsAtHour(12)) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `points convert only when name and rate are both set`() {
        val unset = UsagePricingSettings()
        assertNull(unset.toPoints(12.5))
        assertNull(UsagePricingSettings(pointsName = "积分", pointsPerCurrency = 0.0).toPoints(12.5))
        assertNull(UsagePricingSettings(pointsName = "", pointsPerCurrency = 100.0).toPoints(12.5))
        assertEquals(
            1250.0,
            UsagePricingSettings(pointsName = "积分", pointsPerCurrency = 100.0).toPoints(12.5) ?: -1.0,
            1e-9,
        )
    }

    @Test
    fun `blank currency symbol falls back to dollar`() {
        assertEquals("$", UsagePricingSettings(currencySymbol = "").effectiveCurrencySymbol)
        assertEquals("¥", UsagePricingSettings(currencySymbol = "¥").effectiveCurrencySymbol)
    }

    @Test
    fun `pricing settings survive a json round trip`() {
        val pricing = UsagePricingSettings(
            modelPrices = mapOf(
                "openai/gpt-4" to ModelPrice(pricePerMillion = 2.5, peakPricePerMillion = 5.0, offPeakPricePerMillion = 1.0),
            ),
            providerPricing = mapOf("openai" to ProviderPricing(quota = 100.0, manualUsage = 12.5)),
            peakEnabled = true,
            peakStartHour = 8,
            peakEndHour = 20,
            currencySymbol = "¥",
            pointsName = "积分",
            pointsPerCurrency = 100.0,
        )
        val decoded = JsonInstant.decodeFromString<UsagePricingSettings>(JsonInstant.encodeToString(pricing))
        assertEquals(pricing, decoded)
        // 旧数据缺新字段时按默认值解码（DataStore 升级兼容）
        val legacy = JsonInstant.decodeFromString<UsagePricingSettings>("{}")
        assertEquals(UsagePricingSettings(), legacy)
    }
}
