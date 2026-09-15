package me.rerere.rikkahub.data.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引用改写与引用提取的边界契约。
 *
 * 这两者决定"改名/合并是否安全"：改多了会误伤别的引用，改少了会留下悬空引用。
 * 前缀相似（`$$A` vs `$$AB`）是最容易出错的地方，必须钉住。
 */
class VaultReferenceSyncTest {

    @Test
    fun `renames an exact reference`() {
        assertEquals(
            "Bearer \$\$NEW_NAME",
            VaultReferenceSync.renameInText("Bearer \$\$OLD_NAME", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `does not touch a longer name sharing the prefix`() {
        assertEquals(
            "\$\$OLD_NAME_EXTRA",
            VaultReferenceSync.renameInText("\$\$OLD_NAME_EXTRA", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `renames inside a multi-value list`() {
        assertEquals(
            "\$\$NEW_NAME,\$\$OTHER",
            VaultReferenceSync.renameInText("\$\$OLD_NAME,\$\$OTHER", "OLD_NAME", "NEW_NAME"),
        )
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("OLD_NAME without prefix", VaultReferenceSync.renameInText("OLD_NAME without prefix", "OLD_NAME", "NEW_NAME"))
    }

    @Test
    fun `no-op when names are equal or blank`() {
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "A", "A"))
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "", "B"))
        assertEquals("\$\$A", VaultReferenceSync.renameInText("\$\$A", "A", ""))
    }

    @Test
    fun `extractRefs finds all referenced names`() {
        assertEquals(
            listOf("A", "B"),
            VaultReferenceLocator.extractRefs("x \$\$A y \$\$B z"),
        )
    }

    @Test
    fun `extractRefs returns empty for plain text`() {
        assertEquals(emptyList<String>(), VaultReferenceLocator.extractRefs("no refs here"))
        assertEquals(emptyList<String>(), VaultReferenceLocator.extractRefs(""))
    }

    @Test
    fun `extractRefs deduplicates`() {
        assertEquals(listOf("A"), VaultReferenceLocator.extractRefs("\$\$A and \$\$A"))
    }
}
