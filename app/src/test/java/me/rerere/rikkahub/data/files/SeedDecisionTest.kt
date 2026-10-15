package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Policy matrix for [decideSeedAction] — the pure core of
 * `SkillManager.seedDefaultSkillsIfNeeded`. The regression this pins down: an
 * overwrite-install of a bundled skill must survive the next seed (user override
 * wins), while a genuine bundled-content change still re-seeds for users who
 * never touched the skill.
 */
class SeedDecisionTest {
    @Test
    fun `core skill with matching hash skips`() {
        assertEquals(
            SeedDecision.SKIP,
            decideSeedAction(
                ownedByUs = true,
                targetDirExists = true,
                targetDirNonEmpty = true,
                bundledHash = "abc",
                storedHash = "abc",
            )
        )
    }

    @Test
    fun `core skill with changed bundled hash re-seeds`() {
        assertEquals(
            SeedDecision.SEED,
            decideSeedAction(
                ownedByUs = true,
                targetDirExists = true,
                targetDirNonEmpty = true,
                bundledHash = "new",
                storedHash = "old",
            )
        )
    }

    @Test
    fun `user override beats a changed bundled hash`() {
        // The overwrite-install regression: the user replaced a bundled skill
        // (e.g. a localized agent-core); the next APK ships a different bundled
        // hash, but the user's version must not be clobbered.
        assertEquals(
            SeedDecision.SKIP,
            decideSeedAction(
                ownedByUs = true,
                targetDirExists = true,
                targetDirNonEmpty = true,
                bundledHash = "new-bundled",
                storedHash = "",
                userOverridden = true,
            )
        )
    }

    @Test
    fun `non-core user directory without sentinel is never touched`() {
        assertEquals(
            SeedDecision.SKIP,
            decideSeedAction(
                ownedByUs = false,
                targetDirExists = true,
                targetDirNonEmpty = true,
                bundledHash = "",
                storedHash = "",
            )
        )
    }

    @Test
    fun `non-core empty or missing directory seeds`() {
        assertEquals(
            SeedDecision.SEED,
            decideSeedAction(
                ownedByUs = false,
                targetDirExists = true,
                targetDirNonEmpty = false,
                bundledHash = "",
                storedHash = "",
            )
        )
        assertEquals(
            SeedDecision.SEED,
            decideSeedAction(
                ownedByUs = false,
                targetDirExists = false,
                targetDirNonEmpty = false,
                bundledHash = "",
                storedHash = "",
            )
        )
    }

    @Test
    fun `non-core with sentinel skips even when dir is empty`() {
        // ownedByUs=true (sentinel exists) with empty hashes compares equal -> SKIP.
        // An emptied-but-sentinel-ed directory is left alone, matching the old
        // `if (sentinel.exists()) continue` behavior.
        assertEquals(
            SeedDecision.SKIP,
            decideSeedAction(
                ownedByUs = true,
                targetDirExists = true,
                targetDirNonEmpty = false,
                bundledHash = "",
                storedHash = "",
            )
        )
    }
}
