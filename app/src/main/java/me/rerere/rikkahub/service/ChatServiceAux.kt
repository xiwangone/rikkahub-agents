
package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatService"


    // ---- 生成标题 ----

suspend fun ChatService.generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false,
    ) {
        val shouldGenerate =
            when {
                force -> true
                conversation.title.isBlank() -> true
                else -> false
            }
        if (!shouldGenerate) return

        val fallback = titleFallbackFrom(conversation.currentMessages)

        suspend fun applyTitle(title: String?) {
            if (title.isNullOrBlank()) return
            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                if (shouldWriteTitle(force, it.title)) {
                    saveConversation(conversationId, it.copy(title = title))
                }
            }
        }


        try {
            val settings = settingsStore.settingsFlow.first()
            // Manual regeneration (force=true) remains available when automation is disabled.
            if (!force && !settings.autoGenerateTitle) return
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: error("title model is not configured")
            val provider = model.findProvider(settings.providers)
                ?: error("title model provider is missing")
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) error("title model provider is disabled")

            val providerHandler = providerManager.getProviderByType(provider)
            val result =
                providerHandler.generateText(
                    providerSetting = provider,
                    messages =
                        listOf(
                            UIMessage.user(
                                prompt =
                                    settings.titlePrompt.applyPlaceholders(
                                        "locale" to Locale.getDefault().displayName,
                                        "content" to
                                            conversation.currentMessages
                                                .takeLast(4)
                                                .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                                    ),
                            ),
                        ),
                    params = backgroundTextGenerationParams(model, conversationId),
                )

            applyTitle(result.message.toText().trim().ifBlank { fallback })
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            // Keep diagnostics useful without logging/displaying provider response bodies or credentials.
            AppLog.w(TAG, "generateTitle failed (${failure.javaClass.simpleName})")
            try {
                applyTitle(fallback)
            } catch (fallbackFailure: CancellationException) {
                throw fallbackFailure
            } catch (fallbackFailure: Throwable) {
                AppLog.w(TAG, "generateTitle fallback apply failed (${fallbackFailure.javaClass.simpleName})")
            }
            val now = System.currentTimeMillis()
            val shouldReport = synchronized(titleFailureLock) {
                val previous = titleFailureReportedAtMs[conversationId]
                if (previous == null || now - previous >= 60_000L) {
                    titleFailureReportedAtMs[conversationId] = now
                    true
                } else {
                    false
                }
            }
            if (shouldReport) {
                addError(
                    error = IllegalStateException(str(R.string.chat_title_generation_failed)),
                    conversationId = conversationId,
                    title = str(R.string.chat_title_generation_failed_title),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
            }
        }
    }

    // ---- 生成建议 ----

suspend fun ChatService.generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList()),
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result =
                providerHandler.generateText(
                    providerSetting = provider,
                    messages =
                        listOf(
                            UIMessage.user(
                                settings.suggestionPrompt.applyPlaceholders(
                                    "locale" to Locale.getDefault().displayName,
                                    "content" to
                                        conversation.currentMessages
                                            .takeLast(8)
                                            .joinToString("\n\n") { it.summaryAsText(maxLength = 500) },
                                ),
                            ),
                        ),
                    params = backgroundTextGenerationParams(model, conversationId),
                )
            val suggestions =
                result.message.toText().split("\n")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation =
                conversationRepo.getConversationById(conversationId)
                    ?: sessions[conversationId]?.state?.value
                    ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions =
                        suggestions.take(
                            10,
                        ),
                ),
            )
        }.onFailure {
            // Suggestion generation is auxiliary — log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            AppLog.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * 压缩区段含「仍在等用户审批」的工具调用时阻止 —— 那种调用被折进摘要后审批将无法恢复
     * （用户点了批准也没对象可执行）。
     *
     * ⚠ 判据是 [UIMessagePart.Tool.isPending]（**只算 Pending**），**不是** output 是否为空：
     * 历史遗留的空 output（被拒后未回填 / 生成中断 / 用户取消）已不可能再执行，
     * 压缩掉无妨；用 output.isEmpty() 会把这些陈旧条目误判为待处理，从而一直挡住压缩。
     */
internal fun ChatService.requireNoPendingToolCalls(messages: List<UIMessage>) {
        val hasPendingToolCall =
            messages.any { msg ->
                msg.parts.any { part -> part is UIMessagePart.Tool && part.isPending }
            }
        if (hasPendingToolCall) {
            throw IllegalStateException(
                str(R.string.chat_page_compress_pending_tools),
            )
        }
    }

    /**
     * 压缩是长任务：写回前确认会话没被改动（判据 = 条数 + 首尾 id），
     * 避免用旧快照把压缩期间产生的新内容盖掉。
     */
internal suspend fun ChatService.requireConversationUnchanged(conversationId: Uuid, snapshot: List<UIMessage>) {
        val latest = conversationRepo.getConversationById(conversationId)?.currentMessages ?: return
        if (latest.size != snapshot.size ||
            latest.firstOrNull()?.id != snapshot.firstOrNull()?.id ||
            latest.lastOrNull()?.id != snapshot.lastOrNull()?.id
        ) {
            throw IllegalStateException(
                str(R.string.chat_page_compress_conversation_changed),
            )
        }
    }


suspend fun ChatService.compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
    ): Result<Unit> =
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            // 「Auto」压缩模型是内置 provider 下的占位项，而该 provider 默认禁用：
            // 直接解析它会在「provider 被禁用」处失败，看起来像"点了压缩没反应"。
            // 这里跳过占位与被禁用的 provider，依次回退到当前聊天模型、再到任意启用 provider 的模型。
            val model =
                resolveCompressionModel(settings)
                    ?: throw IllegalStateException(
                        str(R.string.chat_page_compress_model_unavailable),
                    )
            val provider =
                model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

            val providerHandler = providerManager.getProviderByType(provider)

            val maxMessagesPerChunk = 256
            val allMessages = conversation.currentMessages
            AppLog.d(TAG, "压缩开始: total=${allMessages.size} targetTokens=$targetTokens keepRecent=$keepRecentMessages model=${model.id}")

            // Split messages into those to compress and those to keep
            val messagesToCompress: List<UIMessage>
            val messagesToKeep: List<UIMessage>

            if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
                messagesToCompress = allMessages.dropLast(keepRecentMessages)
                messagesToKeep = allMessages.takeLast(keepRecentMessages)
            } else if (keepRecentMessages > 0) {
                // Not enough messages to compress while keeping recent ones
                throw IllegalStateException(str(R.string.chat_page_compress_not_enough_messages))
            } else {
                messagesToCompress = allMessages
                messagesToKeep = emptyList()
            }

            requireNoPendingToolCalls(messagesToCompress)

            // T11: 按 token 预算分块（复用 ContextBudgetPlanner.estimateMessageTokens），
            // 大工具输出块不再因固定 256 消息/块超压缩模型窗口
            fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
                if (messages.size <= maxMessagesPerChunk) return listOf(messages)
                val targetBlockTokens = 6000L
                val chunks = mutableListOf<List<UIMessage>>()
                var current = mutableListOf<UIMessage>()
                var currentTokens = 0L
                for (msg in messages) {
                    val msgTokens = me.rerere.rikkahub.data.ai.ContextBudgetPlanner.estimateMessageTokens(
                        message = msg,
                        documentInlineCapChars = settings.displaySetting.attachmentInlineThreshold,
                    )
                    if (current.isNotEmpty() && currentTokens + msgTokens > targetBlockTokens) {
                        chunks.add(current)
                        current = mutableListOf()
                        currentTokens = 0L
                    }
                    current.add(msg)
                    currentTokens += msgTokens
                }
                if (current.isNotEmpty()) chunks.add(current)
                return chunks
            }

            suspend fun compressMessages(messages: List<UIMessage>): String {
                val contentToCompress =
                    messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) } +
                        ToolHistoryHelper.toolHistoryBlock(messages)
                val prompt =
                    settings.compressPrompt.applyPlaceholders(
                        "content" to contentToCompress,
                        "target_tokens" to targetTokens.toString(),
                        "additional_context" to
                            if (additionalPrompt.isNotBlank()) {
                                "Additional instructions from user: $additionalPrompt"
                            } else {
                                ""
                            },
                        "locale" to Locale.getDefault().displayName,
                    )

                val result =
                    providerHandler.generateText(
                        providerSetting = provider,
                        messages = listOf(UIMessage.user(prompt)),
                        params = backgroundTextGenerationParams(model, conversationId),
                    )
                result.usage?.let { compressUsage ->
                    runCatching { settingsStore.accumulateConvUsage(conversationId.toString(), compressUsage) }
                }

                return result.message.toText()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Failed to generate compressed summary")
            }

            seedLifetimeUsageBeforeCompaction(conversationId, conversation)
            val chunks = splitMessages(messagesToCompress)
            AppLog.d(TAG, "压缩分块: messagesToCompress=${messagesToCompress.size} 块数=${chunks.size}（token 预算 6000/块）")
            val compressedSummaries =
                coroutineScope {
                    chunks
                        .map { chunk -> async { compressMessages(chunk) } }
                        .awaitAll()
                }
            AppLog.d(TAG, "压缩完成: 摘要数=${compressedSummaries.size} 保留=${messagesToKeep.size}")

            // Create new conversation with compressed history as multiple user messages + kept messages
            // 摘要标记为上下文检查点，UI 渲染为分隔线样式
            val newMessageNodes =
                buildList {
                    compressedSummaries.forEach { summary ->
                        add(UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode())
                    }
                    val compactTools =
                        settings.getAssistantById(conversation.assistantId)?.toolOutputCompactTools.orEmpty()
                    addAll(
                        messagesToKeep.map {
                            ToolHistoryHelper.truncateKeptToolOutput(
                                it,
                                settings.toolOutputMaxChars,
                                compactTools,
                                settings.toolOutputCompactMaxChars,
                            ).toMessageNode()
                        },
                    )
                }
            val newConversation =
                conversation.copy(
                    messageNodes = newMessageNodes,
                    chatSuggestions = emptyList(),
                )

            // T10: 压缩前归档原始消息（可追溯；UI 回看后续可选）
            runCatching {
                val archiveJson =
                    kotlinx.serialization.json.Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UIMessage.serializer()),
                        messagesToCompress,
                    )
                compressedArchiveDao.insert(
                    me.rerere.rikkahub.data.db.entity.CompressedArchiveEntity(
                        conversationId = conversationId.toString(),
                        compressedAtMs = System.currentTimeMillis(),
                        archiveJson = archiveJson,
                    )
                )
                AppLog.d(TAG, "压缩归档成功: json=${archiveJson.length} chars")
            }.onFailure { e ->
                AppLog.w(TAG, "归档压缩历史失败: ${e.message}")
            }

            recordAgentTraceMemoryOnCompaction(settings, conversation, messagesToCompress)

            requireConversationUnchanged(conversationId, allMessages)

            saveConversation(conversationId, newConversation)
        }

private suspend fun ChatService.seedLifetimeUsageBeforeCompaction(
    conversationId: Uuid,
    conversation: Conversation,
) {
    runCatching {
        val preTotals = me.rerere.rikkahub.costguards.TokenBudgetTracker.aggregate(conversation)
        settingsStore.ensureConvLifetimeBaseline(
            conversationId.toString(),
            me.rerere.rikkahub.costguards.LifetimeUsage(
                inputTokens = preTotals.inputTokens,
                cachedTokens = preTotals.cachedTokens,
                outputTokens = preTotals.outputTokens,
                turns = preTotals.messageCount,
                costUsd = preTotals.costUsd,
            ),
        )
    }
}

private suspend fun ChatService.recordAgentTraceMemoryOnCompaction(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    conversation: Conversation,
    messagesToCompress: List<UIMessage>,
) {
    runCatching {
        val assistant = settings.getAssistantById(conversation.assistantId)
        if (assistant?.enableMemory == true) {
            val traceDigest = buildAgentExecutionTraceDigest(conversation.title, messagesToCompress)
            if (traceDigest != null) {
                val scopeId =
                    if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                memoryRepository.addMemory(
                    assistantId = scopeId,
                    content = traceDigest,
                    tier = MemoryRepository.TIER_CONDITIONAL,
                )
                AppLog.d(TAG, "已沉淀工具执行轨迹至 conditional 记忆: scope=$scopeId")
            }
        }
    }.onFailure { e ->
        AppLog.w(TAG, "沉淀工具执行轨迹记忆失败: ${e.message}")
    }
}

private fun buildAgentExecutionTraceDigest(
    conversationTitle: String,
    messages: List<UIMessage>,
): String? {
    val executedTools =
        messages
            .asSequence()
            .flatMap { it.parts.asSequence() }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isExecuted }
            .toList()
    if (executedTools.size < 3) return null
    val distinctChain =
        executedTools
            .map { it.toolName }
            .distinct()
            .take(12)
            .joinToString(" -> ")
    val titleLabel = conversationTitle.trim().ifBlank { "Untitled task" }.take(60)
    return "[Agent Trace · $titleLabel] Executed ${executedTools.size} tool steps ($distinctChain)"
}
