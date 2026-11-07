package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5 migration removes the fixed 2k compaction target written by the previous migration: that
 * value was an old application default, not a user choice, and keeping it would pin upgrades to
 * it forever. Any other explicit target must survive.
 */
class PreferenceStoreV5MigrationTest {
    private val migration = PreferenceStoreV5Migration()

    @Test
    fun `legacy 2k target is removed and version becomes 5`() = runBlocking {
        val out = migration.migrate(
            mutablePreferencesOf(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K to 2),
        )

        assertFalse(out.contains(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K))
        assertEquals(5, out[SettingsStore.VERSION])
    }

    @Test
    fun `explicit non legacy target survives`() = runBlocking {
        val out = migration.migrate(
            mutablePreferencesOf(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K to 32),
        )

        assertEquals(32, out[SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K])
        assertEquals(5, out[SettingsStore.VERSION])
    }

    @Test
    fun `absent target stays absent`() = runBlocking {
        val out = migration.migrate(emptyPreferences())

        assertFalse(out.contains(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K))
        assertEquals(5, out[SettingsStore.VERSION])
    }

    @Test
    fun `shouldMigrate only for missing or older versions`() = runBlocking {
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 4)))
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 5)))
    }
}
