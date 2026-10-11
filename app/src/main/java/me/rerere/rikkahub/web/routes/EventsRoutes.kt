package me.rerere.rikkahub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.web.dto.ConversationListInvalidateEvent
import me.rerere.rikkahub.web.dto.FolderListEvent
import me.rerere.rikkahub.web.dto.toDto
import kotlin.time.Duration.Companion.seconds

/**
 * Multiplexed server-sent events stream.
 *
 * A single `/api/events` connection carries several event types, distinguished by the
 * SSE `event:` field, so new event kinds can be added without opening a new connection:
 *  - `settings`                     -> full Settings snapshot
 *  - `conversation_list_invalidate` -> the conversation list for an assistant changed
 *  - `folders`                      -> the folder list for an assistant changed
 *
 * Per-conversation streaming (generation updates) keeps its own dedicated connection
 * at `/api/conversations/{id}/stream`.
 */
fun Route.eventsRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
) {
    sse("/events") {
        heartbeat {
            period = 15.seconds
        }

        // Full settings snapshot; StateFlow emits the current value immediately on connect.
        // P0-47：原本这里 `encodeToString(settings)` 把**整个 Settings 平铺下发** —— 一条 SSE
        // 就能拿走 Web 口令、provider apiKey、桥接口令、备份口令密文、代理口令。
        // 改为按 JSON 结构递归剔除凭证类字段后再发：事件名 `settings` 与其余结构保持不变，
        // 前端订阅逻辑不受影响（只是这些字段的值变成占位串）。
        val settingsEvents = settingsStore.settingsFlow.map { settings ->
            EventPayload(event = "settings", json = JsonInstant.encodeToString(redactSecrets(settings)))
        }

        // Conversation list invalidation, scoped to the currently selected assistant.
        val conversationListEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                combine(
                    conversationRepo.getConversationsOfAssistant(assistantId),
                    chatService.getConversationJobs()
                ) { conversations, generationJobs ->
                    // Key per conversation folds in generation state (so start/stop invalidates the
                    // sidebar even when content isn't persisted) and folderId (so moving a
                    // conversation between folders invalidates other clients' folder views).
                    conversations.map { conversation ->
                        buildString {
                            append(conversation.id)
                            append('|')
                            append(conversation.updateAt.toEpochMilli())
                            append('|')
                            append(generationJobs[conversation.id] != null)
                            append('|')
                            append(conversation.folderId?.toString().orEmpty())
                        }
                    }
                }
                    .distinctUntilChanged()
                    .map { assistantId }
            }
            .map { assistantId ->
                EventPayload(
                    event = "conversation_list_invalidate",
                    json = JsonInstant.encodeToString(
                        ConversationListInvalidateEvent(
                            assistantId = assistantId.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }

        // Folder list for the currently selected assistant (Room flow emits on any change).
        val folderEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                folderRepo.getFoldersOfAssistant(assistantId).map { folders ->
                    EventPayload(
                        event = "folders",
                        json = JsonInstant.encodeToString(
                            FolderListEvent(
                                assistantId = assistantId.toString(),
                                folders = folders.map { it.toDto() }
                            )
                        )
                    )
                }
            }

        merge(settingsEvents, conversationListEvents, folderEvents).collect { payload ->
            send(data = payload.json, event = payload.event)
        }
    }
}

private data class EventPayload(
    val event: String,
    val json: String,
)

/** P0-47：被剔除的字段在这里换成占位串（保持 JSON 形状，前端不会撞上"字段消失"） */
private const val WEB_REDACTED_PLACEHOLDER = "[redacted]"

/**
 * P0-47：SSE `settings` 快照中必须脱敏的字段名。按 **JSON key 名**递归匹配，
 * 因此嵌套的 providers / mcpServers / WebDAV / S3 / TTS / ASR / proxy 等配置块里的
 * 同类凭证同样被覆盖。名单偏保守（宁多剥不误漏），过剥字段的取舍见 results 报告。
 */
private val WEB_SECRET_KEY_NAMES: Set<String> = setOf(
    "webServerAccessPassword",
    "webBridgePassword",
    "backupEncryptionPasswordEnc",
    "proxyPassword",
    "apiKey",
    "api_key",
    "clientSecret",
    "client_secret",
    "accessToken",
    "access_token",
    "refreshToken",
    "refresh_token",
    "password",
    "secret",
    "credential",
    "credentials",
)

private fun redactSecrets(settings: Settings): JsonElement =
    redactSecrets(JsonInstant.encodeToJsonElement(settings))

private fun redactSecrets(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
            put(
                key,
                if (key in WEB_SECRET_KEY_NAMES) {
                    JsonPrimitive(WEB_REDACTED_PLACEHOLDER)
                } else {
                    redactSecrets(value)
                }
            )
        }
    }

    is JsonArray -> buildJsonArray {
        element.forEach { add(redactSecrets(it)) }
    }

    else -> element
}
