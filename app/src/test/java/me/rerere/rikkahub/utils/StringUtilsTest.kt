package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the placeholder/format helpers used across AI prompt building (title/compression). */
class StringUtilsTest {

    @Test
    fun `占位符按对替换`() {
        assertEquals(
            "Hi A, locale zh",
            "Hi {name}, locale {locale}".applyPlaceholders("name" to "A", "locale" to "zh"),
        )
    }

    @Test
    fun `未匹配的占位符保留原样`() {
        assertEquals("a {x} b", "a {x} b".applyPlaceholders("y" to "1"))
    }

    @Test
    fun `token 数按 K M 展示`() {
        assertEquals("174K", 174_000L.formatK())
        assertEquals("1.8M", 1_800_000L.formatK())
        assertEquals("1M", 1_000_000L.formatK())
    }
}
