package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.BackupEncryptionManager
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import org.koin.java.KoinJavaComponent.getKoin
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale


/** `app_backup` 的默认备份项（与设置页默认保持一致的保守集合）。 */
private val DEFAULT_APP_BACKUP_ITEMS = listOf(
    S3Config.BackupItem.DATABASE,
    S3Config.BackupItem.SETTINGS,
    S3Config.BackupItem.AVATARS,
    S3Config.BackupItem.SKILLS,
    S3Config.BackupItem.WORKSPACE_DOCS,
)


    /**
     * `app_backup`：生成 App 数据备份（数据库含**密钥库密文**、设置、头像、技能、工作区文档）。
     *
     * - `target=local`（默认）：只落本地（App 私有 cache）；备份加密开启时产出 `.enc`，
     *   口令取「设置 → 备份」里**已记住的备份口令**——不需要、也不应该把口令发到对话里。
     * - `target=s3` / `webdav`：复用设置里**已配置**的目标，生成后直接上传（加密同理由备份设置决定）。
     *
     * 需人工审批（包内含敏感数据）。
     */
internal fun buildAppBackupTool(settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore): Tool = run {
        Tool(
            name = "app_backup",
            description =
                "Create an app-data backup (database incl. vault ciphertext, settings, avatars, skills, workspace docs) and " +
                    "either save it locally (target=local, default: returns a file path) or upload it to the backup destination " +
                    "already configured in Settings -> Backup (target=s3 or webdav). Encryption follows the app's backup " +
                    "settings; the backup password never needs to be shared in chat. Requires approval.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional backup items, e.g. [\"DATABASE\",\"SETTINGS\",\"AVATARS\",\"SKILLS\",\"WORKSPACE_DOCS\",\"CHAT_FILES\",\"FONTS_IMAGES\",\"TOOL_OUTPUTS\"]. Defaults to a core set.",
                                )
                                put("items", buildJsonObject { put("type", "string") })
                            },
                        )
                        put(
                            "target",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "'local' (default: create the file and return its path), 's3' or 'webdav' (upload to the destination configured in Settings -> Backup).",
                                )
                            },
                        )
                    },
                    required = emptyList(),
                )
            },
            needsApproval = { true },
            execute = { executeAppBackup(it.jsonObject, settingsStore) },
        )
    }

private suspend fun executeAppBackup(
    params: kotlinx.serialization.json.JsonObject,
    settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
): List<UIMessagePart> {
                val requested =
                    params["items"]?.jsonArray
                        ?.mapNotNull { e -> e.jsonPrimitive.contentOrNull }
                        ?.mapNotNull { n -> runCatching { S3Config.BackupItem.valueOf(n.uppercase()) }.getOrNull() }
                        .orEmpty()
                val items = requested.ifEmpty { DEFAULT_APP_BACKUP_ITEMS }
                val target = params["target"]?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { s -> s.isNotBlank() } ?: "local"
                val settings = settingsStore.settingsFlow.value
                val encryptedBySettings = settings.backupEncryptionEnabled

                return when (target) {
                    "s3" -> {
                        val base = settings.s3Configs.firstOrNull() ?: settings.s3Config
                        if (base.endpoint.isBlank()) {
                            listOf(
                                UIMessagePart.Text(
                                    ToolErrors.envelopeFor(
                                        error = "s3_not_configured",
                                        message = "S3 backup is not configured.",
                                        hint = "Configure an S3 target first: Settings -> Backup -> S3.",
                                    ).toString(),
                                ),
                            )
                        } else {
                            getKoin().get<S3Sync>().backupToS3(base.copy(items = items))
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("ok", true)
                                        put("target", "s3")
                                        put("uploaded", true)
                                        put("encrypted", encryptedBySettings)
                                        put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    }.toString(),
                                ),
                            )
                        }
                    }

                    "webdav" -> {
                        val base = settings.webDavConfigs.firstOrNull()
                        if (base == null || base.url.isBlank()) {
                            listOf(
                                UIMessagePart.Text(
                                    ToolErrors.envelopeFor(
                                        error = "webdav_not_configured",
                                        message = "WebDAV backup is not configured.",
                                        hint = "Configure a WebDAV target first: Settings -> Backup -> WebDAV.",
                                    ).toString(),
                                ),
                            )
                        } else {
                            val webItems =
                                items.mapNotNull { s ->
                                    runCatching { WebDavConfig.BackupItem.valueOf(s.name) }.getOrNull()
                                }
                            getKoin().get<WebDavSync>().backup(base.copy(items = webItems))
                            listOf(
                                UIMessagePart.Text(
                                    buildJsonObject {
                                        put("ok", true)
                                        put("target", "webdav")
                                        put("uploaded", true)
                                        put("encrypted", encryptedBySettings)
                                        put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    }.toString(),
                                ),
                            )
                        }
                    }

                    else -> {
                        val plain = getKoin().get<S3Sync>().prepareBackupFile(S3Config(items = items)).file
                        val encryptionManager = getKoin().get<BackupEncryptionManager>()
                        val file =
                            runCatching { encryptionManager.maybeEncrypt(plain) }.getOrElse { e ->
                                return listOf(
                                    UIMessagePart.Text(
                                        ToolErrors.envelopeFor(error = "backup_password_missing", message = "Backup password is missing", hint = "Backup encryption is on but this device has no remembered password. Enter it once in Settings -> Backup (no need to share it in chat).", extra = ToolErrors.extraOf("detail" to JsonPrimitive(e.message.orEmpty()))).toString(),
                                    ),
                                )
                            }
                        listOf(
                            UIMessagePart.Text(
                                buildJsonObject {
                                    put("path", file.absolutePath)
                                    put("size_bytes", file.length())
                                    put("encrypted", file != plain)
                                    put("items", buildJsonArray { items.forEach { add(it.name) } })
                                    put(
                                        "hint",
                                        "Copy this file out (e.g. copy_file) and keep it safe; it contains sensitive data.",
                                    )
                                }.toString(),
                            ),
                        )
                    }
                }
}


internal fun buildTimeTool(): Tool = run {
        Tool(
            name = "get_time_info",
            description = """
                Get the current local date and time info from the device.
                Returns year/month/day, weekday, ISO date/time strings, timezone, and timestamp.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { }
                )
            },
            execute = {
                val now = ZonedDateTime.now()
                val date = now.toLocalDate()
                val time = now.toLocalTime().withNano(0)
                val weekday = now.dayOfWeek
                val payload = buildJsonObject {
                    put("year", date.year)
                    put("month", date.monthValue)
                    put("day", date.dayOfMonth)
                    put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
                    put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                    put("weekday_index", weekday.value)
                    put("date", date.toString())
                    put("time", time.toString())
                    put("datetime", now.withNano(0).toString())
                    put("timezone", now.zone.id)
                    put("utc_offset", now.offset.id)
                    put("timestamp_ms", now.toInstant().toEpochMilli())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

internal fun buildClipboardTool(context: Context): Tool = run {
        Tool(
            name = "clipboard_tool",
            description = """
                Read or write plain text from the device clipboard.
                Use action: read or write. For write, provide text.
                Do NOT write to the clipboard unless the user has explicitly requested it.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                kotlinx.serialization.json.buildJsonArray {
                                    add("read")
                                    add("write")
                                }
                            )
                            put("description", "Operation to perform: read or write")
                        })
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "Text to write to the clipboard (required for write)")
                        })
                    },
                    required = listOf("action")
                )
            },
            execute = {
                val params = it.jsonObject
                val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
                when (action) {
                    "read" -> {
                        val text = context.readClipboardText()
                        val hits = me.rerere.rikkahub.data.ai.tools.local
                            .SensitiveContentDetector.scan(text)
                        val payload = buildJsonObject {
                            put("text", text)
                            if (hits.isNotEmpty()) {
                                put("sensitive_content_detected", true)
                                put(
                                    "warning",
                                    "Clipboard appears to contain sensitive content " +
                                        "(${hits.joinToString { it.name.lowercase() }}). " +
                                        "Do NOT echo the value back to the user, log it, " +
                                        "or include it in summaries or URLs unless they " +
                                        "explicitly ask for it."
                                )
                            }
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    "write" -> {
                        val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                        context.writeClipboardText(text)
                        val payload = buildJsonObject {
                            put("success", true)
                            put("text", text)
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    }

                    else -> error("unknown action: $action, must be one of [read, write]")
                }
            }
        )
    }

internal fun buildTtsTool(eventBus: AppEventBus): Tool = run {
        Tool(
            name = "text_to_speech",
            description = """
                Speak text aloud to the user using the device's text-to-speech engine.
                Use this when the user asks you to read something aloud, or when audio output is appropriate.
                The tool returns immediately; audio plays in the background on the device.
                Provide natural, readable text without markdown formatting.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("text", buildJsonObject {
                            put("type", "string")
                            put("description", "The text to speak aloud")
                        })
                    },
                    required = listOf("text")
                )
            },
            execute = {
                val text = it.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: error("text is required")
                eventBus.emit(AppEvent.Speak(text))
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }
        )
    }

internal fun buildAskUserTool(): Tool = run {
        Tool(
            name = ASK_USER_TOOL_NAME,
            description = """
                Ask the user one or more questions (each may offer suggested options) when you
                need clarification or confirmation. Answers map question IDs to responses.
            """.trimIndent().replace("\n", " "),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("questions", buildJsonObject {
                            put("type", "array")
                            put("description", "List of questions to ask the user")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Unique identifier for this question")
                                    })
                                    put("question", buildJsonObject {
                                        put("type", "string")
                                        put("description", "The question text to display to the user")
                                    })
                                    put("options", buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional list of suggested options for the user to choose from"
                                        )
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                    put("selection_type", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "enum",
                                            kotlinx.serialization.json.buildJsonArray {
                                                add("text")
                                                add("single")
                                                add("multi")
                                            }
                                        )
                                        put(
                                            "description",
                                            "Answer type: text (free text input, default), single (select one option or enter custom text), multi (select options and/or enter custom text)"
                                        )
                                    })
                                })
                                put("required", kotlinx.serialization.json.buildJsonArray {
                                    add("id")
                                    add("question")
                                })
                            })
                        })
                    },
                    required = listOf("questions")
                )
            },
            needsApproval = { true },
            execute = {
                // Reached only when no human-in-the-loop surface intercepted this call. The
                // in-app question card and the Telegram clarify flow both handle ask_user before
                // execute() runs; any other context (cron jobs, sub-agents) has nobody to answer,
                // so return a graceful envelope telling the model to ask in plain text rather than
                // throwing an opaque tool_failed.
                listOf(
                    UIMessagePart.Text(
                        ToolErrors.envelopeFor(error = "ask_user_unavailable", message = "Interactive questions aren't available in this context. Ask your question in your normal reply text instead; the user will read it and answer.", hint = "Ask the question in normal reply text instead.").toString()
                    )
                )
            }
        )
    }

    // 注册表按域分组：原单一 toolRegistry() 394 行触发 LongMethod(120)；
