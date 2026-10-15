package me.rerere.rikkahub.data.ai.tools

import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `use_skill reads metadata directory when display name differs`() = runBlocking {
        val skillDir = tempFolder.newFolder("directory-name")
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: Display Name
                description: Test skill
                ---
                Skill instructions
            """.trimIndent()
        )
        val tool = createSkillTools(
            enabledSkills = setOf("Display Name"),
            allSkills = listOf(
                SkillMetadata(
                    name = "Display Name",
                    description = "Test skill",
                    skillDir = skillDir,
                )
            ),
        ).first { it.name == "use_skill" }

        val result = tool.execute(
            buildJsonObject {
                put("name", "Display Name")
            }
        )

        assertEquals("Skill instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `use_skill body read prefers locale matched SKILL file`() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-CN"))
            val skillDir = tempFolder.newFolder("locale-skill")
            val frontmatter = "---\nname: locale-skill\ndescription: d\n---\n"
            skillDir.resolve("SKILL.md").writeText(frontmatter + "default body")
            skillDir.resolve("SKILL.zh.md").writeText(frontmatter + "中文正文")
            val tool = createSkillTools(
                enabledSkills = setOf("locale-skill"),
                allSkills = listOf(
                    SkillMetadata(name = "locale-skill", description = "d", skillDir = skillDir)
                ),
            ).first { it.name == "use_skill" }

            val result = tool.execute(buildJsonObject { put("name", "locale-skill") })

            assertEquals("中文正文", (result.single() as UIMessagePart.Text).text)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `use_skill body read falls back to default file when locale variant missing`() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ja-JP"))
            val skillDir = tempFolder.newFolder("no-ja-skill")
            skillDir.resolve("SKILL.md").writeText(
                "---\nname: no-ja\ndescription: d\n---\ndefault body"
            )
            val tool = createSkillTools(
                enabledSkills = setOf("no-ja"),
                allSkills = listOf(
                    SkillMetadata(name = "no-ja", description = "d", skillDir = skillDir)
                ),
            ).first { it.name == "use_skill" }

            val result = tool.execute(buildJsonObject { put("name", "no-ja") })

            assertEquals("default body", (result.single() as UIMessagePart.Text).text)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `use_skill explicit path falls back to locale variant`() = runBlocking {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-Hant-TW"))
            val skillDir = tempFolder.newFolder("sidecar-skill")
            // The localized body links sidecars by base name; only the zh variant ships.
            skillDir.resolve("SKILL.zh.md").writeText(
                "---\nname: sidecar\ndescription: d\n---\nsee [SOUL.md](SOUL.md)"
            )
            skillDir.resolve("SOUL.zh.md").writeText("人格・繁体版")

            val tool = createSkillTools(
                enabledSkills = setOf("sidecar"),
                allSkills = listOf(
                    SkillMetadata(name = "sidecar", description = "d", skillDir = skillDir)
                ),
            ).first { it.name == "use_skill" }

            val result = tool.execute(
                buildJsonObject {
                    put("name", "sidecar")
                    put("path", "SOUL.md")
                }
            )

            assertEquals("人格・繁体版", (result.single() as UIMessagePart.Text).text)
        } finally {
            Locale.setDefault(original)
        }
    }
}
