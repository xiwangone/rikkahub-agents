package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.runBlocking

/** Runs a DataMigration step against given preferences without touching any real DataStore. */
internal fun runBlockingMigration(
    prefs: Preferences = androidx.datastore.preferences.core.emptyPreferences(),
    migrate: suspend (Preferences) -> Preferences,
): Preferences = runBlocking { migrate(prefs) }
