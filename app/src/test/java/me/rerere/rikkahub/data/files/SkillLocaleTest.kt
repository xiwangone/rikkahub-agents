package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillLocaleTest {
    @Test
    fun `insertLocaleSuffix inserts before extension`() {
        assertEquals("SKILL.zh.md", SkillLocale.insertLocaleSuffix("SKILL.md", "zh"))
        assertEquals("SOUL.en.md", SkillLocale.insertLocaleSuffix("SOUL.md", "en"))
        assertEquals("data.ja.json", SkillLocale.insertLocaleSuffix("data.json", "ja"))
        assertEquals("README.ko", SkillLocale.insertLocaleSuffix("README", "ko"))
    }

    @Test
    fun `localeVariants keeps script but drops region`() {
        assertEquals(listOf("zh-Hant", "zh"), SkillLocale.localeVariants("zh-Hant-TW"))
        assertEquals(listOf("zh-Hant", "zh"), SkillLocale.localeVariants("zh-Hant-HK"))
        assertEquals(listOf("zh-Hans", "zh"), SkillLocale.localeVariants("zh-Hans-CN"))
        assertEquals(listOf("en"), SkillLocale.localeVariants("en-US"))
        assertEquals(listOf("ko"), SkillLocale.localeVariants("ko-KR"))
        assertEquals(listOf("ja"), SkillLocale.localeVariants("ja"))
        assertEquals(emptyList<String>(), SkillLocale.localeVariants(""))
    }

    @Test
    fun `localizedCandidates orders specific first and always ends with default`() {
        assertEquals(
            listOf("SKILL.zh-Hant.md", "SKILL.zh.md", "SKILL.md"),
            SkillLocale.localizedCandidates("SKILL.md", "zh-Hant-TW")
        )
        assertEquals(
            listOf("SKILL.en.md", "SKILL.md"),
            SkillLocale.localizedCandidates("SKILL.md", "en-US")
        )
        assertEquals(listOf("SKILL.md"), SkillLocale.localizedCandidates("SKILL.md", null))
        assertEquals(listOf("SKILL.md"), SkillLocale.localizedCandidates("SKILL.md", ""))
    }

    @Test
    fun `resolveLocalizedSkillFile prefers closest existing variant`() {
        val root = Files.createTempDirectory("skill-locale").toFile()
        try {
            val skillDir = File(root, "demo").apply { mkdirs() }
            skillDir.resolve("SKILL.md").writeText("default")
            skillDir.resolve("SKILL.zh.md").writeText("zh")
            skillDir.resolve("SKILL.en.md").writeText("en")

            // zh-Hant-TW has no dedicated file: falls through to SKILL.zh.md
            val zhHant = SkillPaths.resolveLocalizedSkillFile(skillDir, "SKILL.md", "zh-Hant-TW")
            assertEquals("SKILL.zh.md", zhHant?.name)
            // exact language match wins over the default
            val en = SkillPaths.resolveLocalizedSkillFile(skillDir, "SKILL.md", "en-US")
            assertEquals("SKILL.en.md", en?.name)
            // no ja translation: default file
            val ja = SkillPaths.resolveLocalizedSkillFile(skillDir, "SKILL.md", "ja")
            assertEquals("SKILL.md", ja?.name)
            // null locale: default file
            val none = SkillPaths.resolveLocalizedSkillFile(skillDir, "SKILL.md", null)
            assertEquals("SKILL.md", none?.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveLocalizedSkillFile prefers script variant over plain language`() {
        val root = Files.createTempDirectory("skill-locale-script").toFile()
        try {
            val skillDir = File(root, "demo").apply { mkdirs() }
            skillDir.resolve("SKILL.md").writeText("default")
            skillDir.resolve("SKILL.zh.md").writeText("zh")
            skillDir.resolve("SKILL.zh-Hant.md").writeText("zh-Hant")

            val resolved = SkillPaths.resolveLocalizedSkillFile(skillDir, "SKILL.md", "zh-Hant-TW")
            assertEquals("SKILL.zh-Hant.md", resolved?.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveLocalizedSkillFile rejects traversal candidates`() {
        val root = Files.createTempDirectory("skill-locale-traversal").toFile()
        try {
            val skillDir = File(root, "demo").apply { mkdirs() }
            File(root, "secret.md").writeText("outside")

            // A relativePath that escapes the skill dir must fail the whole
            // resolution, not silently fall back to the default path.
            assertNull(SkillPaths.resolveLocalizedSkillFile(skillDir, "../secret.md", "zh"))
            assertNull(SkillPaths.resolveLocalizedSkillFile(skillDir, "/etc/passwd", "zh"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `availableLocales lists shipped translations without the default`() {
        val root = Files.createTempDirectory("skill-locale-avail").toFile()
        try {
            val skillDir = File(root, "demo").apply { mkdirs() }
            skillDir.resolve("SKILL.md").writeText("default")
            skillDir.resolve("SKILL.en.md").writeText("en")
            skillDir.resolve("SKILL.zh-Hant.md").writeText("zh-Hant")
            skillDir.resolve("SKILL.zh.md.bak").writeText("not a translation")

            assertEquals(listOf("en", "zh-Hant"), SkillLocale.availableLocales(skillDir, "SKILL.md"))
        } finally {
            root.deleteRecursively()
        }
    }
}
