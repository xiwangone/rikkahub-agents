package me.rerere.rikkahub.data.files

import java.io.File
import java.util.Locale

/**
 * Locale resolution for localized skill files.
 *
 * A skill directory may ship parallel translations of any of its files as
 * `NAME.<lang>.md` siblings (e.g. `SKILL.zh.md`, `SOUL.en.md`). The un-suffixed
 * file is the default-language version — Chinese for the bundled skills, per
 * `docs/skills-spec.md`. Resolution prefers the closest match for the device
 * locale and falls back to the default file, so a skill that ships only the
 * default file keeps working in every language.
 *
 * Language tags use BCP-47 with the same script distinction as the app's
 * `values-*` resource directories:
 *
 * | App resource dir     | Locale tag  | File suffix  |
 * | -------------------- | ----------- | ------------ |
 * | values/              | en          | `.en.md`     |
 * | values-zh/           | zh          | `.zh.md`     |
 * | values-b+zh+Hant/    | zh-Hant     | `.zh-Hant.md`|
 * | values-ja/           | ja          | `.ja.md`     |
 * | values-ko-rKR/       | ko          | `.ko.md`     |
 * | values-ru/           | ru          | `.ru.md`     |
 * | values-ar/           | ar          | `.ar.md`     |
 *
 * Region subtags are deliberately not part of the file name: `zh-Hant-TW` and
 * `zh-Hant-HK` both resolve to `SKILL.zh-Hant.md`, keeping the file set small.
 */
object SkillLocale {
    /** Languages with a `values-*` translation in the app. */
    val SUPPORTED_LANGUAGES: List<String> = listOf("en", "zh", "ja", "ko", "ru", "ar")

    /** Script subtag for Traditional Chinese (`values-b+zh+Hant`). */
    const val SCRIPT_HANT = "Hant"

    /** Current device locale as a BCP-47 tag (e.g. `zh-Hant-TW`, `en-US`). */
    fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()

    /**
     * Insert a locale suffix before the file's extension:
     * `("SOUL.md", "zh")` -> `"SOUL.zh.md"`; `("data.json", "en")` -> `"data.en.json"`;
     * `("README", "ja")` -> `"README.ja"`.
     */
    fun insertLocaleSuffix(fileName: String, localeSuffix: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) "$fileName.$localeSuffix" else
            fileName.substring(0, dot) + "." + localeSuffix + fileName.substring(dot)
    }

    /**
     * Locale suffixes to try for [localeTag], most specific first, without region:
     * `"zh-Hant-TW"` -> `["zh-Hant", "zh"]`; `"en-US"` -> `["en"]`; `"ja"` -> `["ja"]`.
     */
    fun localeVariants(localeTag: String): List<String> {
        val parts = localeTag.split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val language = parts[0].lowercase()
        val script = parts.firstOrNull { it.length == 4 && it.first().isLetter() }
            ?.replaceFirstChar { it.uppercase() }
        return buildList {
            if (script != null) add("$language-$script")
            add(language)
        }.distinct()
    }

    /**
     * Candidate file names for [fileName] under [localeTag], best match first,
     * always ending with [fileName] itself as the default-language fallback.
     *
     * `("SKILL.md", "zh-Hant-TW")` -> `["SKILL.zh-Hant.md", "SKILL.zh.md", "SKILL.md"]`
     * `("SKILL.md", "en-US")`      -> `["SKILL.en.md", "SKILL.md"]`
     * `("SKILL.md", null)`         -> `["SKILL.md"]`
     */
    fun localizedCandidates(fileName: String, localeTag: String?): List<String> {
        if (localeTag.isNullOrBlank()) return listOf(fileName)
        return (localeVariants(localeTag).map { insertLocaleSuffix(fileName, it) } + fileName).distinct()
    }

    /**
     * Locale suffixes of the localized variants that actually exist for [fileName]
     * in [skillDir], e.g. `["en", "zh-Hant"]` when `SKILL.en.md` / `SKILL.zh-Hant.md`
     * are present. The default file is not included. Useful for diagnostics and
     * for the UI to surface which translations a skill ships.
     */
    fun availableLocales(skillDir: File, fileName: String): List<String> {
        val prefix = fileName.substringBeforeLast('.', fileName) + "."
        val suffix = "." + fileName.substringAfterLast('.', "")
        return skillDir.listFiles()
            ?.mapNotNull { file ->
                val name = file.name
                // The default file itself (SKILL.md) must not be reported as a locale.
                if (!file.isFile || name == fileName) return@mapNotNull null
                if (!name.startsWith(prefix) || !name.endsWith(suffix)) return@mapNotNull null
                name.removePrefix(prefix).removeSuffix(suffix).takeIf { it.isNotBlank() }
            }
            ?.sorted()
            ?: emptyList()
    }
}
