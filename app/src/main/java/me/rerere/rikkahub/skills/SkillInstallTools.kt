package me.rerere.rikkahub.skills

import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager

private const val TAG = "SkillInstallTools"

/**
 * Phase 16 audit fix — outcome of the auto-enable step that rides along with a successful
 * `skill_install_from_*` call. Decoupled from the DataStore write so it can be unit-tested
 * against a plain in-memory `Set<String>` without an Android Context.
 *
 * @property autoEnabled true only when the skill was ALREADY enabled for the calling
 * assistant before the install. Installs never enable a skill themselves — the user's
 * toggle in Settings is the only thing that puts a name into enabledSkills.
 * @property updatedEnabledSkills always null: installs never modify enabledSkills.
 */
data class AutoEnableOutcome(
    val autoEnabled: Boolean,
    val detail: String,
    val updatedEnabledSkills: Set<String>?,
)

/**
 * Decide the enable state after an install. Skills are never auto-enabled: what the user
 * toggles in Settings is authoritative, so an install only writes files to disk and reports
 * whether the skill happens to be enabled already.
 *
 *  - Already enabled → no-op, stays enabled (idempotent re-install).
 *  - Not enabled (brand-new, or previously disabled) → left disabled, and the response
 *    tells the user exactly where to turn it on.
 */
fun decideAutoEnable(
    enabledSkills: Set<String>,
    skillName: String,
    existedBefore: Boolean,
): AutoEnableOutcome = when {
    skillName in enabledSkills -> AutoEnableOutcome(
        autoEnabled = true,
        detail = "Skill is already enabled for the active assistant and ready to use.",
        updatedEnabledSkills = null,
    )

    existedBefore -> AutoEnableOutcome(
        autoEnabled = false,
        detail = "Skill content was updated. It is NOT auto-enabled — turn it on in " +
            "Settings > Assistants > Skills before calling use_skill.",
        updatedEnabledSkills = null,
    )

    else -> AutoEnableOutcome(
        autoEnabled = false,
        detail = "Skill installed to disk but NOT auto-enabled — turn it on in " +
            "Settings > Assistants > Skills before calling use_skill.",
        updatedEnabledSkills = null,
    )
}

/**
 * Report the enable state for a freshly-installed [skillName]: resolve the calling assistant
 * and decide via [decideAutoEnable]. Never writes enabledSkills — installing a skill does not
 * enable it. Returns the outcome so the install tool can surface it in the response envelope.
 *
 * The guarded write block is kept for safety: [decideAutoEnable] always returns a null
 * [AutoEnableOutcome.updatedEnabledSkills], so no persistence happens. A DataStore failure
 * (or any unexpected error) is still reported as `autoEnabled = false` with a recovery hint —
 * the install itself already succeeded, so we never fail the whole tool call over this step.
 */
private suspend fun applyAutoEnable(
    settingsStore: SettingsStore,
    skillName: String,
    existedBeforeInstall: Boolean,
): AutoEnableOutcome {
    return try {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getCurrentAssistant()
        val outcome = decideAutoEnable(assistant.enabledSkills, skillName, existedBeforeInstall)
        val newSet = outcome.updatedEnabledSkills
        if (newSet != null) {
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map { a ->
                        if (a.id == assistant.id) a.copy(enabledSkills = newSet) else a
                    }
                )
            }
        }
        outcome
    } catch (t: Throwable) {
        Log.w(TAG, "applyAutoEnable: failed to auto-enable '$skillName'", t)
        AutoEnableOutcome(
            autoEnabled = false,
            detail = "Skill installed but could not be auto-enabled. Toggle it on in " +
                "Settings > Assistants > Skills before calling use_skill.",
            updatedEnabledSkills = null,
        )
    }
}

/**
 * Snapshot of every skill name on disk. Captured *before* the import writes, so the install
 * tool can tell a brand-new skill apart from a re-install of an existing (possibly disabled)
 * one — the importer itself doesn't surface that distinction.
 */
private fun existingSkillNames(skillManager: SkillManager): Set<String> =
    runCatching { skillManager.listSkills().map { it.name }.toSet() }.getOrDefault(emptySet())

/**
 * Phase 16 — `skill_install_from_url` LLM tool.
 *
 * Always requires per-call approval (NO_ALWAYS_ALLOW). Skills installed this way can ride
 * along with the assistant's full tool surface — the user must consent every single time
 * because the source URL is whatever the LLM said it was.
 *
 * Skills are NEVER auto-enabled for the calling assistant: the user's toggle in Settings is
 * the only thing that puts a name into enabledSkills. The response reports whether the skill
 * happens to be enabled already — see `auto_enabled` / `auto_enabled_detail`.
 */
fun skillInstallFromUrlTool(
    importer: SkillUrlImporter,
    settingsStore: SettingsStore,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_url",
    description = """
        Download and install a skill from a URL. Accepts native (RikkaHub markdown +
        frontmatter), openclaw markdown, or Hermes JSON formats. Tool names are best-effort
        transcoded to RikkaHub equivalents. The user reviews and approves the URL + final
        skill name before save. Skills are NEVER auto-enabled — the user's toggle in
        Settings is the only thing that enables a skill; if it is not already enabled,
        `auto_enabled` is false and `auto_enabled_detail` tells where to turn it on.
        Returns
        { ok, name, format, source_url, auto_enabled, auto_enabled_detail } on success.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "http(s) URL of the skill markdown or JSON. Loopback / private IPs are rejected.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override for the skill's saved name (1..40 chars, [a-z0-9-_]). Defaults to whatever the source declares.")
                })
            },
            required = listOf("url"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val url = json.jsonObject["url"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_url", "url is required")
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromUrl(url, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(settingsStore, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_url", url)
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

/**
 * Phase 16 — `skill_install_from_text`. Companion to `skill_install_from_url` for sources
 * the URL importer can't reach: authenticated servers, private intranets, SSH-only hosts,
 * MCP-relayed skill servers, or content the user just pasted into chat.
 *
 * Compose with any read-capable tool to bridge the gap, e.g.:
 *  - `ssh_exec_saved(host_label="myserver", command="cat ~/skills/morning.md")` → pipe stdout
 *  - `termux_run_command(command="curl -H 'Authorization: Bearer …' https://my.server/skill.md")`
 *  - any MCP tool returning a skill body
 *
 * Same approval gate as `skill_install_from_url` — every install is reviewed individually
 * (NO_ALWAYS_ALLOW). Same format detect + tool-name transcode + persistence as the URL path,
 * and the same no-auto-enable behavior: installing a skill never turns it on.
 */
fun skillInstallFromTextTool(
    importer: SkillUrlImporter,
    settingsStore: SettingsStore,
    skillManager: SkillManager,
): Tool = Tool(
    name = "skill_install_from_text",
    description = """
        Install a skill from raw text content (markdown or JSON) the LLM already has —
        useful when the source is behind auth and was fetched via ssh_exec / termux_run_command /
        an MCP tool. Accepts the same three formats as skill_install_from_url (native /
        openclaw / Hermes). The user reviews + approves the source label + skill name.
        Skills are NEVER auto-enabled — the user's toggle in Settings is the only thing
        that enables a skill; if it is not already enabled, `auto_enabled` is false and
        `auto_enabled_detail` tells where to turn it on. Returns
        { ok, name, format, source_label, auto_enabled, auto_enabled_detail } on success.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Full skill body — markdown with frontmatter (native / openclaw) or JSON (Hermes). Capped at 256KB.")
                })
                put("source_label", buildJsonObject {
                    put("type", "string")
                    put("description", "Where this came from: URL, ssh host label, MCP tool, 'clipboard', etc. Persisted in the skill's frontmatter for audit.")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional override for the skill's saved name (1..40 chars, [a-z0-9-_]). Defaults to whatever the source declares.")
                })
            },
            required = listOf("content"),
        )
    },
    needsApproval = { true },
    execute = { json ->
        val content = json.jsonObject["content"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool err("missing_content", "content is required")
        val sourceLabel = json.jsonObject["source_label"]?.jsonPrimitive?.contentOrNull
        val override = json.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val namesBeforeInstall = existingSkillNames(skillManager)
        when (val r = importer.importFromText(content, sourceLabel, override)) {
            is SkillUrlImporter.Result.Err -> err(r.code, r.detail)
            is SkillUrlImporter.Result.Ok -> {
                val existedBefore = r.metadata.name in namesBeforeInstall
                val outcome = applyAutoEnable(settingsStore, r.metadata.name, existedBefore)
                val payload = buildJsonObject {
                    put("ok", true)
                    put("name", r.metadata.name)
                    put("description", r.metadata.description)
                    put("format", r.format.name.lowercase())
                    put("source_label", JsonPrimitive(sourceLabel ?: "imported"))
                    put("auto_enabled", outcome.autoEnabled)
                    put("auto_enabled_detail", outcome.detail)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        }
    },
)

private fun err(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("ok", false)
        put("error", code)
        put("detail", detail)
    }.toString()))
