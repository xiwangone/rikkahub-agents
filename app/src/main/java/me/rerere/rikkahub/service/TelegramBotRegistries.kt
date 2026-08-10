package me.rerere.rikkahub.service

/**
 * Telegram bot 静态注册表与工具（从 TelegramBotService 提取，减负大文件）。
 *
 * 包含：
 * - ApprovalPromptRegistry：审批键盘 (toolCallId → chatId/messageId) 注册表（FIFO 上限 256）
 * - RejectedSenderLog：最近被拒发送者日志（通知栏读）
 * - RecentCommandsLog：每 chat 最近指令（上限 8，TTL 过期）
 * - BUILT_IN_COMMANDS：内置斜杠命令菜单（Telegram 自动补全）
 */

object TelegramBotRegistries {
    /**
     * Process-scoped registry of (toolCallId → (chatId, messageId)) for in-flight
     * approval prompts. Lets the callback handler edit/clean up the right Telegram
     * message when a tap arrives.
     *
     * Soft-capped at MAX_ENTRIES (FIFO of insertion order). Without the cap, a model
     * that produces many never-resolved approval prompts (user away for days) would
     * leak entries until process death. The cap evicts oldest first so any in-flight
     * approval the user might still tap stays addressable.
     */
    object ApprovalPromptRegistry {
        data class Entry(
            val chatId: Long,
            val messageId: Long,
        )

        private const val MAX_ENTRIES = 256
        private val byCallId = java.util.concurrent.ConcurrentHashMap<String, Entry>()

        // Tracks insertion order so we know which entry is oldest when we hit the cap.
        // Bounded LinkedHashMap on the same key set would do this for us, but we need
        // concurrent reads, so we pair the concurrent map with a synchronised deque.
        private val insertionOrder = java.util.concurrent.LinkedBlockingDeque<String>()

        fun register(
            toolCallId: String,
            chatId: Long,
            messageId: Long,
        ) {
            val wasNew = byCallId.put(toolCallId, Entry(chatId, messageId)) == null
            if (wasNew) {
                insertionOrder.addLast(toolCallId)
                // Evict oldest entries while we're over the cap. If pollFirst returns a
                // key that was already removed from byCallId (e.g. after a clear()), the
                // remove is a no-op — that's fine, we keep looping until we're under cap.
                while (byCallId.size > MAX_ENTRIES) {
                    val oldest = insertionOrder.pollFirst() ?: break
                    byCallId.remove(oldest)
                }
            }
            // If the key was already present, byCallId is updated in-place above. The
            // existing position in insertionOrder is still correct for FIFO eviction
            // (re-registering the same toolCallId re-uses the original slot). No
            // structural change to insertionOrder needed.
        }

        fun get(toolCallId: String): Entry? = byCallId[toolCallId]

        fun clear(toolCallId: String) {
            if (byCallId.remove(toolCallId) != null) {
                insertionOrder.remove(toolCallId)
            }
        }

        /** Drop every prompt we registered for [chatId]. Called on /new so a reset
         *  conversation doesn't leave stale (toolCallId → messageId) lookups behind. */
        fun clearChat(chatId: Long) {
            val toRemove =
                byCallId.entries
                    .asSequence()
                    .filter { it.value.chatId == chatId }
                    .map { it.key }
                    .toList()
            for (k in toRemove) {
                byCallId.remove(k)
                insertionOrder.remove(k)
            }
        }

        /** Snapshot of every entry whose chatId == [chatId]. Used by /stop and /new
         *  to edit each registered keyboard message in place to "Cancelled" before
         *  clearing the registry — without this the user sees orphan buttons forever. */
        fun snapshotForChat(chatId: Long): List<Pair<String, Entry>> =
            byCallId.entries
                .asSequence()
                .filter { it.value.chatId == chatId }
                .map { it.key to it.value }
                .toList()
    }

    /**
     * Process-scoped log of the most recently rejected (non-whitelisted) sender. The
     * foreground notification reads this so a user who enabled the bot with an empty
     * whitelist can DM the bot once, see the rejection in the notification, and copy
     * their chat_id into the whitelist UI. Without this you'd have to dig through
     * logcat to discover your own Telegram chat_id.
     */
    data class RejectedSender(
        val senderId: Long,
        val chatId: Long,
        val atMs: Long,
    )

    object RejectedSenderLog {
        @Volatile private var last: RejectedSender? = null

        fun record(
            senderId: Long,
            chatId: Long,
        ) {
            last = RejectedSender(senderId, chatId, System.currentTimeMillis())
        }

        fun latest(): RejectedSender? = last

        fun clear() {
            last = null
        }
    }

    /**
     * Process-scoped per-chat ring of recently-handled slash commands. Used to inject
     * "the user just ran /model X" context into the next LLM turn so the model knows
     * what the user did via the app's UI rather than via tool calls. Trims by TTL on
     * read so stale entries vanish without a sweeper.
     */
    object SlashCommandLog {
        private const val MAX_PER_CHAT = 8

        // MutableList values are always accessed under the list's own monitor. CHM
        // provides safe get/putIfAbsent so we can obtain the list atomically; all
        // mutations then go through synchronized(list) so record() and recent() never
        // interleave on the same entry. Using compute() directly was incorrect because
        // it held the CHM bucket lock — not list's monitor — while mutating the list,
        // allowing a concurrent recent() call holding list's monitor to see a
        // partially-updated list.
        private val byChat = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Pair<String, Long>>>()

        fun record(
            chatId: Long,
            display: String,
        ) {
            val now = System.currentTimeMillis()
            val list = byChat.getOrPut(chatId) { mutableListOf() }
            synchronized(list) {
                list.add(display to now)
                while (list.size > MAX_PER_CHAT) list.removeAt(0)
            }
        }

        fun recent(
            chatId: Long,
            ttlMs: Long,
        ): List<Pair<String, Long>> {
            val list = byChat[chatId] ?: return emptyList()
            val cutoff = System.currentTimeMillis() - ttlMs
            synchronized(list) {
                list.removeAll { (_, ts) -> ts < cutoff }
                return list.toList()
            }
        }
    }

    /**
     * The single source of truth for the bot's built-in slash-command menu. Each entry
     * is (command-without-slash, description shown in Telegram's autocomplete menu).
     * Order matches what the user sees when they tap "/" in the chat.
     *
     * Telegram caps each description at 256 chars and the command at 32 chars; keep
     * descriptions short.
     */
    val BUILT_IN_COMMANDS: List<Pair<String, String>> =
        listOf(
            "start" to "Show a quick welcome and the most useful commands",
            "help" to "List every built-in slash command",
            "new" to "Start a fresh conversation (clears history)",
            "stop" to "Cancel the current generation immediately",
            "status" to "Show service state, current model, assistant, and rate limit",
            "model" to "Show or switch the chat model. Usage: /model [name]",
            "ratelimit" to "Show or set the assistant's max output tokens. Usage: /ratelimit [number|clear]",
            "doctor" to "Run app diagnostics — perms, services, DB, network, Termux",
            "stream" to "Show or toggle auto-streamed screenshots. Usage: /stream [on|off]",
        )
}
