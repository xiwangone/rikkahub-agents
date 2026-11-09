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
}
