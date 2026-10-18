package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SettingsDecodeTest {
    @Test
    fun `missing stored setting uses schema default`() {
        assertEquals(listOf("schema-default"), decodePersistedSetting(null, listOf("schema-default")))
    }

    @Test
    fun `valid stored setting decodes`() {
        assertEquals(listOf("provider-a"), decodePersistedSetting("[\"provider-a\"]", emptyList<String>()))
    }

    @Test
    fun `malformed present setting fails instead of returning a default`() {
        try {
            decodePersistedSetting("not-json", emptyList<String>())
            fail("Malformed persisted data must not silently become a default")
        } catch (_: IllegalArgumentException) {
            // Expected: the caller surfaces this as a failed initialization.
        }
    }
}
