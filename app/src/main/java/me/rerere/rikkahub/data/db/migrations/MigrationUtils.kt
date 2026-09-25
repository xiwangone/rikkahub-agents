package me.rerere.rikkahub.data.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.migrateToolNodes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/**
 * 当 `table` 已含 `column` 时返回 true。
 *
 * 手写迁移可能跑在**来自备份/其他构建的库**上 —— 那些库可能已经带了该列（见
 * `ImportedDatabaseReconciler` 与历史上 "duplicate column name" 的首启崩溃，issues #10/#11/#105）。
 * SQLite 的 `ALTER TABLE … ADD COLUMN` **不是幂等的**，所以每条这样的语句都用本函数守卫。
 */
internal fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    runCatching {
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            if (nameIndex >= 0) {
                while (!found && cursor.moveToNext()) {
                    found = cursor.getString(nameIndex) == column
                }
            }
            found
        }
    }.getOrElse { false }

internal val partTypeMapping = mapOf(
    "Text" to "text",
    "UIMessagePart.Text" to "text",
    "me.rerere.ai.ui.UIMessagePart.Text" to "text",
    "Image" to "image",
    "UIMessagePart.Image" to "image",
    "me.rerere.ai.ui.UIMessagePart.Image" to "image",
    "Video" to "video",
    "UIMessagePart.Video" to "video",
    "me.rerere.ai.ui.UIMessagePart.Video" to "video",
    "Audio" to "audio",
    "UIMessagePart.Audio" to "audio",
    "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
    "Document" to "document",
    "UIMessagePart.Document" to "document",
    "me.rerere.ai.ui.UIMessagePart.Document" to "document",
    "Reasoning" to "reasoning",
    "UIMessagePart.Reasoning" to "reasoning",
    "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
    "Search" to "search",
    "UIMessagePart.Search" to "search",
    "me.rerere.ai.ui.UIMessagePart.Search" to "search",
    "ToolCall" to "tool_call",
    "UIMessagePart.ToolCall" to "tool_call",
    "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
    "ToolResult" to "tool_result",
    "UIMessagePart.ToolResult" to "tool_result",
    "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
    "Tool" to "tool",
    "UIMessagePart.Tool" to "tool",
    "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
)

internal fun migrateMessagesJson(messagesJson: String): String {
    return runCatching {
        val element = JsonInstant.parseToJsonElement(messagesJson)
        val migrated = migrateMessagesElement(element)
        if (migrated == element) messagesJson else JsonInstant.encodeToString(migrated)
    }.getOrElse { messagesJson }
}

internal fun migrateMessagesElement(element: JsonElement): JsonElement {
    val rootArray = element as? JsonArray ?: return element
    val migratedArray = JsonArray(
        rootArray.map { message ->
            val messageObject = message as? JsonObject ?: return@map message
            val partsElement = messageObject["parts"] as? JsonArray ?: return@map message
            val migratedParts = migratePartsArray(partsElement)
            if (migratedParts == partsElement) {
                message
            } else {
                JsonObject(messageObject.toMutableMap().apply {
                    put("parts", migratedParts)
                })
            }
        }
    )
    return if (migratedArray == rootArray) element else migratedArray
}

/**
 * One `message_node` row as loaded for Migration_15_16's tool-node merge.
 */
internal data class ToolNodeMigrationRow(
    val id: String,
    val messages: List<UIMessage>,
    val selectIndex: Int,
)

/**
 * Pure decision function behind Migration_15_16: given one conversation's ordered rows
 * (already parsed), decide what (if anything) should be written back.
 *
 * Returns null when nothing should be persisted for this conversation, which covers every
 * case that must NOT delete-then-reinsert:
 *  - [rows] is empty (nothing to do).
 *  - [hasUnparsableRow] is true: at least one row in this conversation failed to parse and was
 *    excluded from [rows]. migrateToolNodes never sees that row, so writing back its output
 *    would delete every row for the conversation and reinsert only the parsed subset:
 *    silently dropping the unparsable row's messages forever. Leaving the whole conversation
 *    untouched is the only choice that can't lose data; the caller is expected to also guard
 *    the migrateToolNodes call itself, since it runs inside one big transaction shared by
 *    every conversation and a single throw must not roll back everything already migrated.
 *  - migrateToolNodes made no change to the parsed rows.
 */
internal fun migrateConversationNodes(
    rows: List<ToolNodeMigrationRow>,
    hasUnparsableRow: Boolean,
): List<ToolNodeMigrationRow>? {
    if (rows.isEmpty() || hasUnparsableRow) return null

    val migrated = rows.migrateToolNodes(
        getMessages = { it.messages },
        setMessages = { row, msgs -> row.copy(messages = msgs) }
    )

    val changed = migrated.size != rows.size ||
        migrated.zip(rows).any { (a, b) -> a.messages != b.messages }

    return if (changed) migrated else null
}

internal fun migratePartsArray(partsElement: JsonArray): JsonArray {
    return JsonArray(
        partsElement.map { part ->
            val partObject = part as? JsonObject ?: return@map part
            val typeValue = partObject["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val mappedType = typeValue?.let { partTypeMapping[it] } ?: typeValue

            var updatedPart: JsonObject = partObject
            if (mappedType != null && mappedType != typeValue) {
                updatedPart = JsonObject(partObject.toMutableMap().apply {
                    put("type", JsonPrimitive(mappedType))
                })
            }

            val outputElement = updatedPart["output"] as? JsonArray ?: return@map updatedPart
            val migratedOutput = migratePartsArray(outputElement)
            if (migratedOutput == outputElement) {
                updatedPart
            } else {
                JsonObject(updatedPart.toMutableMap().apply {
                    put("output", migratedOutput)
                })
            }
        }
    )
}
