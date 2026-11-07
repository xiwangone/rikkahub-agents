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
 * V4 migration fills defaults for the compaction / retry settings introduced after the original
 * schema, without overwriting explicit user values.
 */
class PreferenceStoreV4MigrationTest {
    private val migration = PreferenceStoreV4Migration()

    @Test
    fun `empty preferences get defaults and version 4`() = runBlocking {
        val out = migration.migrate(emptyPreferences())

        assertEquals(4, out[SettingsStore.VERSION])
        assertEquals(false, out[SettingsStore.ENABLE_AUTO_COMPACTION])
        assertEquals("PERCENT", out[SettingsStore.AUTO_COMPACTION_THRESHOLD_MODE])
        assertEquals(80, out[SettingsStore.AUTO_COMPACTION_THRESHOLD_PERCENT])
        assertEquals(8, out[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K])
        assertEquals(5, out[SettingsStore.AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS])
        assertEquals(5, out[SettingsStore.RESPONSE_STREAM_MAX_RETRIES])
    }

    @Test
    fun `explicit user values are preserved`() = runBlocking {
        val prefs = mutablePreferencesOf(
            SettingsStore.ENABLE_AUTO_COMPACTION to true,
            SettingsStore.AUTO_COMPACTION_THRESHOLD_MODE to "TOKENS",
            SettingsStore.AUTO_COMPACTION_THRESHOLD_PERCENT to 55,
            SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K to 32,
            SettingsStore.AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS to 12,
            SettingsStore.RESPONSE_STREAM_MAX_RETRIES to 2,
            SettingsStore.VERSION to 3,
        )

        val out = migration.migrate(prefs)

        assertEquals(true, out[SettingsStore.ENABLE_AUTO_COMPACTION])
        assertEquals("TOKENS", out[SettingsStore.AUTO_COMPACTION_THRESHOLD_MODE])
        assertEquals(55, out[SettingsStore.AUTO_COMPACTION_THRESHOLD_PERCENT])
        assertEquals(32, out[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K])
        assertEquals(12, out[SettingsStore.AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS])
        assertEquals(2, out[SettingsStore.RESPONSE_STREAM_MAX_RETRIES])
        assertEquals(4, out[SettingsStore.VERSION])
    }

    @Test
    fun `summary target stays absent so the runtime can use the model percentage default`() = runBlocking {
        val out = migration.migrate(emptyPreferences())

        assertFalse(out.contains(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K))
    }

    @Test
    fun `shouldMigrate only for missing or older versions`() = runBlocking {
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        assertTrue(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 3)))
        assertFalse(migration.shouldMigrate(mutablePreferencesOf(SettingsStore.VERSION to 4)))
    }
}
