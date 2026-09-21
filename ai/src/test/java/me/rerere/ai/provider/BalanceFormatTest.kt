package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [formatBalance]：多字段余额展示（标签 + 单位 + 后缀）与旧单字段配置的兼容。
 *
 * 契约：
 * - `fields` 非空 → 按 `标签 单位值后缀` 组装、以 ` · ` 连接；
 * - `fields` 为空 → 回退到旧的 `resultPath`（数值两位小数）；
 * - 两者都空 → 返回空串（不显示余额）；
 * - 单项求值失败只影响该项，不影响其它字段。
 */
class BalanceFormatTest {

    private fun body(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `multi fields render label unit and suffix`() {
        val option = BalanceOption(
            fields = listOf(
                BalanceField(label = "Monthly", path = "credits.monthly", unit = "$"),
                BalanceField(label = "5h", path = "win.fiveHour.used", unit = "$", suffix = "/14"),
            ),
        )
        val out = formatBalance(body("""{"credits":{"monthly":58.48},"win":{"fiveHour":{"used":0.42}}}"""), option)
        assertEquals("Monthly $58.48 · 5h $0.42/14", out)
    }

    @Test
    fun `field without label shows value only`() {
        val option = BalanceOption(fields = listOf(BalanceField(path = "v")))
        assertEquals("3.50", formatBalance(body("""{"v":3.5}"""), option))
    }

    @Test
    fun `falls back to legacy single resultPath`() {
        val option = BalanceOption(resultPath = "data.total")
        assertEquals("12.30", formatBalance(body("""{"data":{"total":12.3}}"""), option))
    }

    @Test
    fun `blank option renders nothing`() {
        assertEquals("", formatBalance(body("{}"), BalanceOption()))
    }

    @Test
    fun `broken field only blanks that field`() {
        val option = BalanceOption(
            fields = listOf(
                BalanceField(label = "A", path = "missing.deep.path", unit = "$"),
                BalanceField(label = "B", path = "ok", unit = "$"),
            ),
        )
        val out = formatBalance(body("""{"ok":3}"""), option)
        assertTrue("应保留可求值的字段: $out", out.contains("B $3.00"))
    }
}
