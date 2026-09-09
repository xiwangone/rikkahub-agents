package me.rerere.rikkahub.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [decideAutoEnable], the pure decision behind the enable-state step that rides along
 * with a successful `skill_install_from_*` call. The invariant under test: installs NEVER
 * enable a skill — the user's toggle in Settings is the only thing that puts a name into
 * enabledSkills. `updatedEnabledSkills` must therefore be null in every branch, so no DataStore
 * write is ever needed. The DataStore path ([applyAutoEnable]) is exercised on-device; this
 * nails down the branching.
 */
class SkillAutoEnableTest {

    @Test
    fun `new skill - installed but NOT auto-enabled, no write needed`() {
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core"),
            skillName = "zip-and-send",
            existedBefore = false,
        )

        assertFalse(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("NOT auto-enabled"))
        assertTrue(outcome.detail.contains("Settings > Assistants > Skills"))
    }

    @Test
    fun `re-install of an already-enabled skill - stays enabled, no write needed`() {
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core", "zip-and-send"),
            skillName = "zip-and-send",
            existedBefore = true,
        )

        assertTrue(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("already enabled"))
    }

    @Test
    fun `re-install of a previously-disabled skill - stays disabled`() {
        // Skill exists on disk but is NOT in the enabled set: the user explicitly
        // disabled it. A content update must not silently flip it back on.
        val outcome = decideAutoEnable(
            enabledSkills = setOf("agent-core"),
            skillName = "zip-and-send",
            existedBefore = true,
        )

        assertFalse(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
        assertTrue(outcome.detail.contains("NOT auto-enabled"))
    }

    @Test
    fun `new skill into an empty enabled set - still NOT auto-enabled`() {
        val outcome = decideAutoEnable(
            enabledSkills = emptySet(),
            skillName = "morning-briefing",
            existedBefore = false,
        )

        assertFalse(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
    }

    @Test
    fun `already-enabled skill that was somehow not on disk before - still a no-op`() {
        // Defensive: enabled-set membership wins over the existedBefore flag.
        val outcome = decideAutoEnable(
            enabledSkills = setOf("zip-and-send"),
            skillName = "zip-and-send",
            existedBefore = false,
        )

        assertTrue(outcome.autoEnabled)
        assertNull(outcome.updatedEnabledSkills)
    }

    @Test
    fun `no branch ever returns a non-null enabled set`() {
        // The whole point of the change: installing a skill can never modify
        // enabledSkills, whatever the input combination.
        for (enabled in listOf(emptySet(), setOf("agent-core"), setOf("zip-and-send"))) {
            for (name in listOf("zip-and-send", "morning-briefing")) {
                for (existedBefore in listOf(true, false)) {
                    val outcome = decideAutoEnable(enabled, name, existedBefore)
                    assertNull("non-null set for $enabled/$name/existedBefore=$existedBefore",
                        outcome.updatedEnabledSkills)
                }
            }
        }
    }
}
