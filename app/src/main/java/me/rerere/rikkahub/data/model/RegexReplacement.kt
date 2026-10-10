package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.log.AppLog
import kotlin.text.Regex

/** Preserve the current text if a configured replacement has invalid group syntax. */
@Suppress("TooGenericExceptionCaught") // java.util.regex.Matcher reports unknown numeric groups as IndexOutOfBoundsException.
internal fun replaceRegexSafely(input: String, regex: Regex, replacement: String): String =
    try {
        input.replace(regex = regex, replacement = replacement)
    } catch (error: IndexOutOfBoundsException) {
        AppLog.w("RegexReplacement", "Invalid numeric replacement group", error)
        input
    } catch (error: IllegalArgumentException) {
        AppLog.w("RegexReplacement", "Invalid regex replacement syntax", error)
        input
    }
