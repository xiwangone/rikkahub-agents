package me.rerere.rikkahub.ui.components.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 坐标轴刻度计算（[buildAxisScale] / [ChartAxisScale]）的回归测试。
 *
 * 覆盖边界：单点、全零、极值、负值、对数轴，以及用户指定范围 / includeZero / 空数据 / 冲突范围。
 * 期望值用 Python 按同一算法逐项验算过（浮点按 delta 断言），避免"手算期望"写错。
 */
class ChartScaleTest {

    private fun linear(
        values: List<Double>,
        userMin: Double? = null,
        userMax: Double? = null,
        includeZero: Boolean = false,
    ): ChartAxisScale = buildAxisScale(values, userMin, userMax, log = false, includeZero = includeZero)

    private fun logScale(
        values: List<Double>,
        userMin: Double? = null,
        userMax: Double? = null,
    ): ChartAxisScale = buildAxisScale(values, userMin, userMax, log = true, includeZero = false)

    // ---------- 单点 ----------

    @Test
    fun singleValueExpandsToVisibleRange() {
        val s = linear(listOf(5.0))
        assertEquals(4.4, s.min, 1e-9)
        assertEquals(5.6, s.max, 1e-9)
        assertEquals(7, s.ticks.size)
        assertEquals(4.4, s.ticks.first(), 1e-9)
        assertEquals(5.6, s.ticks.last(), 1e-9)
        assertTrue(s.ticks.any { abs(it - 5.0) < 1e-9 })
        assertEquals(0.5f, s.fraction(5.0), 1e-6f)
        assertEquals(1, s.tickDecimals)
    }

    @Test
    fun singleValueWithIncludeZeroAnchorsAtZero() {
        val s = linear(listOf(5.0), includeZero = true)
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(5.0, s.max, 1e-9)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), s.ticks)
        assertEquals(0, s.tickDecimals)
    }

    // ---------- 全零 ----------

    @Test
    fun allZerosExpandToUnitRange() {
        val s = linear(listOf(0.0, 0.0, 0.0), includeZero = true)
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(1.0, s.max, 1e-9)
        assertEquals(6, s.ticks.size)
        assertEquals(0.0, s.ticks.first(), 1e-9)
        assertEquals(1.0, s.ticks.last(), 1e-9)
        assertEquals(0.0f, s.fraction(0.0), 1e-6f)
        assertEquals(1, s.tickDecimals)
    }

    @Test
    fun allZerosWithoutIncludeZeroSpanSymmetricRange() {
        val s = linear(listOf(0.0, 0.0, 0.0))
        assertEquals(-1.0, s.min, 1e-9)
        assertEquals(1.0, s.max, 1e-9)
        assertEquals(listOf(-1.0, -0.5, 0.0, 0.5, 1.0), s.ticks)
    }

    // ---------- 极值 ----------

    @Test
    fun extremeLargeValuesKeepNiceTicks() {
        val s = linear(listOf(2e15, 3e15))
        assertEquals(2e15, s.min, 1.0)
        assertEquals(3e15, s.max, 1.0)
        assertEquals(6, s.ticks.size)
        assertEquals(2e15, s.ticks.first(), 1.0)
        assertEquals(3e15, s.ticks.last(), 1.0)
        assertEquals(0.0f, s.fraction(2e15), 1e-6f)
        assertEquals(1.0f, s.fraction(3e15), 1e-6f)
        assertEquals(0.5f, s.fraction(2.5e15), 1e-6f)
        assertEquals(0, s.tickDecimals)
    }

    @Test
    fun extremeSmallValuesKeepRelativeRange() {
        val s = linear(listOf(1e-9, 2e-9))
        assertEquals(1e-9, s.min, 1e-15)
        assertEquals(2e-9, s.max, 1e-15)
        assertEquals(6, s.ticks.size)
        assertEquals(0.0f, s.fraction(1e-9), 1e-6f)
        assertEquals(1.0f, s.fraction(2e-9), 1e-6f)
        // 注意：步长 2e-10 < 1e-6，decimalsForStep 的 1e-6 绝对容差在 d=0 就命中，
        // tickDecimals 返回 0（刻度标签会显示成 "0"）。此处锁定当前行为，是否符合预期待确认。
        assertEquals(0, s.tickDecimals)
    }

    // ---------- 负值 ----------

    @Test
    fun negativeValuesWithIncludeZeroReachZero() {
        val s = linear(listOf(-5.0, -1.0), includeZero = true)
        assertEquals(-5.0, s.min, 1e-9)
        assertEquals(0.0, s.max, 1e-9)
        assertEquals(listOf(-5.0, -4.0, -3.0, -2.0, -1.0, 0.0), s.ticks)
        assertEquals(0.0f, s.fraction(-5.0), 1e-6f)
        assertEquals(0.5f, s.fraction(-2.5), 1e-6f)
        assertEquals(1.0f, s.fraction(0.0), 1e-6f)
    }

    @Test
    fun negativeValuesWithoutIncludeZeroStayNegative() {
        val s = linear(listOf(-5.0, -1.0))
        assertEquals(-5.0, s.min, 1e-9)
        assertEquals(-1.0, s.max, 1e-9)
        assertEquals(listOf(-5.0, -4.0, -3.0, -2.0, -1.0), s.ticks)
        assertEquals(0.0f, s.fraction(-5.0), 1e-6f)
        assertEquals(1.0f, s.fraction(-1.0), 1e-6f)
    }

    // ---------- 对数轴 ----------

    @Test
    fun logScaleUsesPowersOfTen() {
        val s = logScale(listOf(1.0, 10.0, 100.0, 1000.0))
        assertTrue(s.log)
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(1000.0, s.max, 1e-9)
        assertEquals(listOf(1.0, 10.0, 100.0, 1000.0), s.ticks)
        assertEquals(0, s.tickDecimals)
    }

    @Test
    fun logScaleFractionIsLogarithmic() {
        val s = logScale(listOf(1.0, 1000.0))
        assertEquals(0.0f, s.fraction(1.0), 1e-6f)
        assertEquals(1.0f, s.fraction(1000.0), 1e-6f)
        // 对数轴上 10 位于 1 与 1000 的三分之一处（线性轴会是 ~1%）
        assertEquals(1f / 3f, s.fraction(10.0), 1e-6f)
        assertEquals(2f / 3f, s.fraction(100.0), 1e-6f)
    }

    @Test
    fun logScaleSkipsNonPositiveValues() {
        val s = logScale(listOf(0.0, -5.0, 10.0))
        assertTrue(s.log)
        assertEquals(10.0, s.min, 1e-9)
        assertEquals(100.0, s.max, 1e-9)
        assertEquals(listOf(10.0, 100.0), s.ticks)
    }

    @Test
    fun logScaleSingleValueExpandsOneDecade() {
        val s = logScale(listOf(5.0))
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(10.0, s.max, 1e-9)
        assertEquals(listOf(1.0, 10.0), s.ticks)
    }

    @Test
    fun logScaleWideRangeThinsTicks() {
        val s = logScale(listOf(1.0, 1e12))
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(1e12, s.max, 1.0)
        assertEquals(listOf(1.0, 1e3, 1e6, 1e9, 1e12), s.ticks)
    }

    @Test
    fun logScaleHonorsUserRange() {
        val s = logScale(listOf(5.0, 50.0), userMin = 1.0, userMax = 10000.0)
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(10000.0, s.max, 1e-9)
        assertEquals(listOf(1.0, 10.0, 100.0, 1000.0, 10000.0), s.ticks)
    }

    // ---------- 用户范围 / includeZero / 空数据 / 冲突 ----------

    @Test
    fun userMinMaxAreUsedVerbatim() {
        val s = linear(listOf(1.0, 2.0, 3.0), userMin = 0.0, userMax = 10.0)
        // 用户指定的 min/max 不再按 nice-step 取整
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(10.0, s.max, 1e-9)
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), s.ticks)
        assertEquals(0, s.tickDecimals)
    }

    @Test
    fun includeZeroExpandsRangeToZero() {
        val withZero = linear(listOf(5.0, 10.0), includeZero = true)
        assertEquals(0.0, withZero.min, 1e-9)
        assertEquals(10.0, withZero.max, 1e-9)

        val withoutZero = linear(listOf(5.0, 10.0))
        assertEquals(5.0, withoutZero.min, 1e-9)
        assertEquals(10.0, withoutZero.max, 1e-9)
        assertEquals(listOf(5.0, 6.0, 7.0, 8.0, 9.0, 10.0), withoutZero.ticks)
    }

    @Test
    fun emptyValuesFallBackToUnitRange() {
        val s = linear(emptyList())
        assertEquals(0.0, s.min, 1e-9)
        assertEquals(1.0, s.max, 1e-9)
        assertEquals(6, s.ticks.size)
    }

    @Test
    fun conflictingUserMinMaxKeepsGivenBounds() {
        val s = linear(listOf(1.0), userMin = 10.0, userMax = 0.0)
        assertEquals(10.0, s.min, 1e-9)
        assertEquals(0.0, s.max, 1e-9)
        // min > max 时刻度无法生成：锁定当前行为（空刻度表）
        assertTrue(s.ticks.isEmpty())
    }

    // ---------- 比例映射 ----------

    @Test
    fun fractionMapsEndpointsLinearly() {
        val s = linear(listOf(2.0, 8.0))
        assertEquals(0.0f, s.fraction(2.0), 1e-6f)
        assertEquals(1.0f, s.fraction(8.0), 1e-6f)
        assertEquals(0.5f, s.fraction(5.0), 1e-6f)
    }

    @Test
    fun fractionOfDegenerateRangeIsHalf() {
        val s = ChartAxisScale(min = 3.0, max = 3.0, ticks = emptyList(), log = false, tickDecimals = 0)
        assertEquals(0.5f, s.fraction(1e9), 1e-6f)
    }
}
