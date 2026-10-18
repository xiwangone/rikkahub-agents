package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.utils.JsonInstant

/** Missing values may use a schema default; present malformed values must propagate. */
internal inline fun <reified T> decodePersistedSetting(raw: String?, default: T): T =
    if (raw == null) default else JsonInstant.decodeFromString<T>(raw)
