package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the AI tool layer's pure functions:
 *  - [renderShellPreset]: the `${name}` / `${name:-default}` placeholder contract. Without
 *    this, the ${name:-default} form silently failed to be overridden by preset_args and fell
 *    back to the shell default (a real bug caught only by manual testing).
 *  - [limitDiffOutput]: long-line truncation and total-output cap so a diff never overflows
 *    the message/context (mirrors the DiffView long-line crash class of bug).
 */
class AiToolFunctionsTest {

    private fun args(vararg pairs: Pair<String, String>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // ---- renderShellPreset ----

    @Test
    fun `presetArgs 覆盖带默认值的占位符`() {
        val template = "dir \${dir:-/workspace} n \${n:-12}"
        assertEquals("dir /x n 5", renderShellPreset("t", template, args("dir" to "/x", "n" to "5")))
    }

    @Test
    fun `缺省时带默认值占位符保留原样交给 shell`() {
        val template = "grep \${pattern} \${dir:-app/src}"
        assertEquals("grep Foo \${dir:-app/src}", renderShellPreset("t", template, args("pattern" to "Foo")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `纯占位符缺参数时直接报错`() {
        renderShellPreset("t", "run \${file}", args("other" to "x"))
    }

    @Test
    fun `纯占位符提供值则替换`() {
        assertEquals("run /a/b", renderShellPreset("t", "run \${file}", args("file" to "/a/b")))
    }

    // ---- limitDiffOutput ----

    @Test
    fun `短行原样保留`() {
        assertEquals("+a\n-b", limitDiffOutput("+a\n-b"))
    }

    @Test
    fun `超长行截断并加标记`() {
        val long = "x".repeat(500)
        val out = limitDiffOutput("+$long")
        assertTrue(out.length < 500)
        assertTrue(out.startsWith("+xxx"))
        assertTrue(out.contains("<line truncated>"))
    }

    @Test
    fun `总输出超过上限时截断`() {
        // 200 行 × 400 字符 ≈ 80k，超过 40k 上限 → 应触发截断标记
        val lines = (0 until 200).joinToString("\n") { "+" + "y".repeat(400) }
        val out = limitDiffOutput(lines)
        assertTrue(out.contains("<diff truncated"))
        assertTrue(out.length <= 40_000 + 200)
    }
}
