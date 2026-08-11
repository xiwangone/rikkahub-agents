package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillManagerSeedDecisionTest {
    @Test
    fun `owned directory with matching hash is skipped`() {
        val decision = decideSeedAction(
            ownedByUs = true,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "abc123",
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `owned directory with differing hash is re-seeded`() {
        val decision = decideSeedAction(
            ownedByUs = true,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "old-hash",
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    @Test
    fun `unowned non-empty directory is skipped regardless of hash`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = true,
            targetDirNonEmpty = true,
            bundledHash = "abc123",
            storedHash = "old-hash",
        )

        assertEquals(SeedDecision.SKIP, decision)
    }

    @Test
    fun `missing directory is seeded`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = false,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
        )

        assertEquals(SeedDecision.SEED, decision)
    }

    @Test
    fun `unowned empty directory is seeded`() {
        val decision = decideSeedAction(
            ownedByUs = false,
            targetDirExists = true,
            targetDirNonEmpty = false,
            bundledHash = "abc123",
            storedHash = "",
        )

        assertEquals(SeedDecision.SEED, decision)
    }
}
