package me.rerere.rikkahub.data.ai

/**
 * Single place for assembling the system prompt that is sent to every provider.
 *
 * Callers provide pre-rendered sections so the ordering and formatting live in one spot
 * rather than being reimplemented in GenerationLoop and the provider adapters.
 *
 * Ordering is **stable-first**: the assistant prompt and tool prompts (byte-identical turn
 * to turn) come first, then the volatile sections (memory, recent chats, per-call
 * addendum) that change between turns. This lets prompt caching work: auto-caching
 * providers (OpenAI/DeepSeek/Grok/Gemini) reuse the stable byte-prefix, and OpenRouter's
 * explicit cache_control breakpoint is placed at the stable/volatile boundary (see
 * ChatCompletionsAPI). Volatile text in the prefix busts the cache every turn, which is
 * what happened before when memory was enabled.
 */
class SystemPromptBuilder {

    /**
     * Returns the system prompt split into `(stable, volatile)`.
     * - stable: assistant prompt + tool cost guidance + tool prompts.
     * - volatile: memory + recent chats + per-call addendum.
     * Either may be blank.
     */
    fun buildSections(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): Pair<String, String> {
        val stable = buildString {
            if (assistantPrompt.isNotBlank()) append(assistantPrompt)
            if (toolPrompts.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("Tool cost guidance: prefer low-cost text tools before expensive visual or broad tools. Use read_window_tree/browser_get_text before screenshots when text is enough, and avoid repeating high-cost tools unless the state likely changed.")
                toolPrompts.forEachIndexed { index, toolPrompt ->
                    if (index > 0) appendLine()
                    append(toolPrompt)
                }
            }
        }.trim()

        val volatile = buildString {
            // 可迭代的模型行为指导放在 volatile 段开头（紧跟 stable）：位于缓存断点之后，
            // 未来调整这些指令不会使 stable 缓存前缀失效。
            if (toolPrompts.isNotEmpty()) {
                appendLine("Context economy: for large tool outputs (API responses, logs, file dumps), save the full result to a file and return only a summary or key lines instead of pasting everything into the conversation. Run searches in small focused batches (2-3 queries at a time) with precise keywords rather than one broad multi-query blast, and wait to see results before issuing the next batch.")
                appendLine("Token usage: on long conversations, call check_token_usage (when available) to self-monitor token consumption and cached-token ratio; proactively suggest or trigger context compression when nearing the context limit.")
                appendLine("Tool discovery: low-frequency tools may expose only a short description and an empty parameter schema. Call get_tool_schema with the exact tool name before retrying such a tool; once loaded, its full schema remains available in this conversation.")
                // 凭证使用约定：仅在确实注入了凭证相关工具时才追加，避免无关会话被塞入无用规则。
                // 放在 volatile 段（缓存断点之后），调整这段话不会让 stable 缓存前缀失效。
                if (toolPrompts.any { it.contains("vault_") }) {
                    appendLine(
                        "Credentials: when something needs a secret (API key, token, password, private key), " +
                            "refer to it BY NAME instead of handling plaintext — never ask the user to paste a secret " +
                            "into chat, and never write secret values into configuration, tool arguments, or files. " +
                            "Use vault_credential_names to discover available names; before renaming or deleting a " +
                            "credential, check vault_credential_refs and vault_dangling_refs so no configuration is " +
                            "left broken. References are resolved inside the app; the secret value is never returned.",
                    )
                }
            }
            if (memoryPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(memoryPrompt)
            }
            if (recentChatsPrompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append(recentChatsPrompt)
            }
            if (!systemAddendum.isNullOrBlank()) {
                if (isNotEmpty()) appendLine()
                append(systemAddendum)
            }
        }.trim()

        return stable to volatile
    }

    /** Combined single-string prompt (stable then volatile), for callers/providers that do
     *  not split into cache blocks. */
    fun build(
        assistantPrompt: String,
        memoryPrompt: String = "",
        recentChatsPrompt: String = "",
        toolPrompts: List<String> = emptyList(),
        systemAddendum: String? = null,
    ): String {
        val (stable, volatile) = buildSections(
            assistantPrompt, memoryPrompt, recentChatsPrompt, toolPrompts, systemAddendum
        )
        return listOf(stable, volatile).filter { it.isNotBlank() }.joinToString("\n")
    }
}
