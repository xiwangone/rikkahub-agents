package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.optString
import me.rerere.rikkahub.data.ai.tools.ToolErrors
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.AppIconManager
import me.rerere.rikkahub.utils.AppIconPreset
import me.rerere.rikkahub.utils.AppIconResolution

/**
 * 列出或切换 App 桌面启动图标（基于预设 `<activity-alias>`）。
 *
 * Android 系统要求桌面图标在打包时编入 APK 资源，因此运行时仅支持在内置预设间切换：
 * `action="list"` 查看可用预设与当前生效项；`action="set"` 切换指定 `preset`（或传 `"default"` 恢复默认）。
 */
fun updateAppIconTool(
    context: Context,
    settingsStore: SettingsStore,
): Tool = Tool(
    name = "update_app_icon",
    description = """
        List or switch the app's launcher (home screen) icon among bundled presets. Use action='list' to inspect available presets and the current active icon, or action='set' with preset (e.g. 'yao_diamond' (default), 'yao_aura', 'yao_prism', 'dual_chat', 'agent_core', 'terminal_cli', 'legacy_yao', 'rikkahub_classic', 'rikkahub_outline') to switch.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "Operation: 'list' or 'set' (defaults to 'set' when preset is provided, otherwise 'list').")
                })
                put("preset", buildJsonObject {
                    put("type", "string")
                    put("description", "Preset id or alias when action='set' (e.g. 'yao_diamond', 'yao_aura', 'yao_prism', 'dual_chat', 'agent_core', 'terminal_cli', 'legacy_yao', 'rikkahub_classic', 'rikkahub_outline', 'default').")
                })
            },
            required = emptyList(),
        )
    },
    execute = { params ->
        val obj = params.jsonObject
        val presetQuery = obj.optString("preset")?.trim().orEmpty()
        val rawAction = obj.optString("action")?.trim()?.lowercase().orEmpty()
        val action = rawAction.ifEmpty { if (presetQuery.isNotEmpty()) "set" else "list" }

        val currentPreset = settingsStore.settingsFlow.first().displaySetting.appIconPreset
        when (action) {
            "list" -> {
                val payload = buildJsonObject {
                    put("ok", true)
                    put("current", currentPreset.id)
                    put("presets", buildJsonArray {
                        AppIconPreset.entries.forEach { preset ->
                            add(buildJsonObject {
                                put("id", preset.id)
                                put("description", preset.styleSummary)
                                put("active", preset == currentPreset)
                            })
                        }
                    })
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "set" -> {
                if (presetQuery.isEmpty()) {
                    return@Tool appIconErrEnvelope(
                        error = ToolErrors.INVALID_ARGUMENT,
                        message = "preset is required when action='set'",
                        hint = "Call with action='list' to see available presets.",
                    )
                }
                val localizedLabels = AppIconPreset.entries.associateWith { context.getString(it.labelRes) }
                val resolved = when (val r = AppIconManager.resolvePreset(presetQuery, localizedLabels)) {
                    is AppIconResolution.Found -> r.preset
                    is AppIconResolution.NotFound ->
                        return@Tool appIconErrEnvelope(
                            error = ToolErrors.NOT_FOUND,
                            message = "Unknown app icon preset '$presetQuery'",
                            hint = "Call with action='list' to inspect bundled presets.",
                            extra = ToolErrors.extraOf("available" to r.availableIds.joinToString(", ")),
                        )
                    is AppIconResolution.Ambiguous ->
                        return@Tool appIconErrEnvelope(
                            error = ToolErrors.INVALID_ARGUMENT,
                            message = "Ambiguous app icon preset '$presetQuery'",
                            hint = "Pass one of the exact preset ids.",
                            extra = ToolErrors.extraOf("matches" to r.matches.joinToString(", ")),
                        )
                }

                settingsStore.update { old ->
                    old.copy(displaySetting = old.displaySetting.copy(appIconPreset = resolved))
                }
                AppIconManager.scheduleLauncherIcon(context, resolved)

                val payload = buildJsonObject {
                    put("ok", true)
                    put("preset", resolved.id)
                    put("previous", currentPreset.id)
                    put("description", resolved.styleSummary)
                    put("note", "Icon preference saved and applied in-app; launcher icon updates automatically when returning to the home screen.")
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> appIconErrEnvelope(
                error = ToolErrors.INVALID_ARGUMENT,
                message = "Unsupported action '$action'; expected 'list' or 'set'.",
            )
        }
    },
)

private fun appIconErrEnvelope(
    error: String,
    message: String,
    hint: String? = null,
    extra: Map<String, JsonElement> = emptyMap(),
): List<UIMessagePart.Text> = listOf(
    UIMessagePart.Text(
        ToolErrors.envelopeFor(error = error, message = message, hint = hint, extra = extra).toString()
    )
)
