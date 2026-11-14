package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class AutolinkTrimTest {

    @Test
    fun `clean link is unchanged`() {
        assertEquals(
            "https://example.com/path" to "",
            splitAutolink("https://example.com/path"),
        )
    }

    @Test
    fun `trailing fullwidth period is cut off`() {
        assertEquals(
            "https://example.com/path" to "。",
            splitAutolink("https://example.com/path。"),
        )
    }

    @Test
    fun `trailing fullwidth comma and following text are cut off`() {
        assertEquals(
            "https://example.com/path" to "，继续说",
            splitAutolink("https://example.com/path，继续说"),
        )
    }

    @Test
    fun `trailing chinese characters are cut off`() {
        assertEquals(
            "https://example.com/foo" to "吧",
            splitAutolink("https://example.com/foo吧"),
        )
    }

    @Test
    fun `trailing fullwidth parenthesis note is cut off`() {
        assertEquals(
            "https://example.com/path" to "（括号说明）",
            splitAutolink("https://example.com/path（括号说明）"),
        )
    }

    @Test
    fun `bracket followed by space and word is cut off`() {
        assertEquals(
            "https://example.com/foo" to "] 结束",
            splitAutolink("https://example.com/foo] 结束"),
        )
    }

    @Test
    fun `merged links keep first link and preserve second as plain text`() {
        assertEquals(
            "https://example.com/foo" to "。https://example.com/bar",
            splitAutolink("https://example.com/foo。https://example.com/bar"),
        )
    }

    @Test
    fun `trailing ascii period is cut off`() {
        assertEquals(
            "https://example.com/path" to ".",
            splitAutolink("https://example.com/path."),
        )
    }

    @Test
    fun `balanced parentheses are kept`() {
        val url = "https://en.wikipedia.org/wiki/PC_Tools_(Central_Point_Software)"
        assertEquals(url to "", splitAutolink(url))
    }

    @Test
    fun `unbalanced closing parenthesis is cut off`() {
        assertEquals(
            "https://example.com/foo_(bar)" to ")",
            splitAutolink("https://example.com/foo_(bar))"),
        )
    }

    @Test
    fun `query string is kept`() {
        val url = "https://example.com/a?x=1&y=2"
        assertEquals(url to "", splitAutolink(url))
    }

    @Test
    fun `ipv6 literal is kept`() {
        assertEquals(
            "https://[::1]" to "",
            splitAutolink("https://[::1]"),
        )
    }

    @Test
    fun `non bare links are unchanged`() {
        assertEquals("#section" to "", splitAutolink("#section"))
        assertEquals("/workspace/out.png" to "", splitAutolink("/workspace/out.png"))
        assertEquals("mailto:a@b.com" to "", splitAutolink("mailto:a@b.com"))
        assertEquals("" to "", splitAutolink(""))
    }

    // ── splitAutolinkParts：渲染层用（code span 误判 / 前缀 / 普通文本） ───────

    @Test
    fun `inline code wrapping a url becomes code, not a link`() {
        // 词法器把行内代码当 autolink：反引号会被当链接文本显示、下划线越界。
        assertEquals(
            AutolinkParts(prefix = "", url = "", trailing = "，输入码", code = "https://example.com/x"),
            splitAutolinkParts("`https://example.com/x`，输入码"),
        )
    }

    @Test
    fun `text before the url is split off as prefix`() {
        assertEquals(
            AutolinkParts(prefix = "打开", url = "https://example.com/x", trailing = "，然后"),
            splitAutolinkParts("打开https://example.com/x，然后"),
        )
    }

    @Test
    fun `clean url has no prefix and no code`() {
        assertEquals(
            AutolinkParts(prefix = "", url = "https://example.com/x", trailing = ""),
            splitAutolinkParts("https://example.com/x"),
        )
    }

    @Test
    fun `plain text without a url stays plain`() {
        assertEquals(
            AutolinkParts(prefix = "没有链接", url = ""),
            splitAutolinkParts("没有链接"),
        )
    }
}
