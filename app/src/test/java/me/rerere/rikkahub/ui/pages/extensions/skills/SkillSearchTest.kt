package me.rerere.rikkahub.ui.pages.extensions.skills

import java.io.File
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillSearchTest {
    private fun skill(name: String, description: String) = SkillMetadata(name, description, skillDir = File(name))

    @Test fun filtersByNameOrDescriptionIgnoringCaseAndWhitespace() {
        val all = listOf(skill("Alpine", "POSIX shell"), skill("Planner", "daily notes"))
        assertEquals(listOf("Alpine"), filterSkills(all, "  ALPINE ").map { it.name })
        assertEquals(listOf("Alpine"), filterSkills(all, "posix").map { it.name })
        assertEquals(all, filterSkills(all, " "))
        assertEquals(emptyList<SkillMetadata>(), filterSkills(all, "missing"))
    }
}
