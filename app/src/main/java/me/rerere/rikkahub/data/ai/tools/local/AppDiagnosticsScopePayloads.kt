package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ContextLedger
import me.rerere.rikkahub.data.ai.GenerationRunTracker
import me.rerere.rikkahub.data.ai.tools.LocalToolCatalog
import me.rerere.rikkahub.data.ai.tools.SurfaceTier
import me.rerere.rikkahub.data.ai.tools.TierSource
import me.rerere.rikkahub.data.ai.tools.ToolCapabilities
import me.rerere.rikkahub.data.ai.tools.ToolSurfacePolicy
import me.rerere.rikkahub.data.ai.tools.ToolUsageTracker
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry

/**
 * AI 自诊断/自管理工具（第一批，纯读，风险零）。
 *
 * 复用现有组件：
 *  - get_app_health: DoctorChecks.runAll() 的 8 类检查快照
 *  - read_app_logs:   AppLog 缓冲 + LogRedactor 脱敏
 *  - test_model:      提供商「测试连接」核心逻辑（非流式/流式/工具调用）
 */

// ---------- list_enabled_tools ----------

/**
 * 当前启用的本地工具选项（按分类）。只读。
 * 用于回答"我现在能用哪些工具"，也可与 [tool_usage_stats] 对照找出"启用了却从未用过"的项。
 */
internal suspend fun enabledToolsPayload(settingsStore: SettingsStore): String {
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val enabled = assistant.localTools
            val byCategory =
                buildJsonObject {
                    LocalToolCatalog.groups().forEach { (category, options) ->
                        val names = options.filter { it in enabled }.map { it.toString() }
                        if (names.isNotEmpty()) {
                            put(category.id, buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                }
            val payload =
                buildJsonObject {
                    put("assistant", assistant.name)
                    put("enabledOptionCount", enabled.size)
                    put("byCategory", byCategory)
                    put(
                        "note",
                        "本地工具按「设置 → 工具」的分类勾选；未勾选的工具不会注入给模型。选项名对应一组工具，具体工具名见 tool_surface_report。",
                    )
                }
    return payload.toString()
}

// ---------- runs ----------

/**
 * 生成结果归因的只读视图（**App 采集与展示**；评估与结论由 AI 侧做）。
 *
 * 每次生成一条：`outcome` = COMPLETED / USER_CANCELLED / TIMEOUT / LOOP_GUARD /
 * NETWORK_ERROR / RATE_LIMIT / API_ERROR / UNKNOWN；失败细分在 `errorKind`（复用 FailureKind 名字）。
 * 只记枚举与计数，**不含消息内容**；开关 = 工具统计开关（关时不增长）。
 */
@Suppress("LongMethod")
internal fun runsPayload(
    context: Context,
    params: JsonObject,
): String {
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, 200)
    val reset = params["reset"]?.jsonPrimitive?.booleanOrNull ?: false
    if (reset) GenerationRunTracker.clear(context)
    val state = GenerationRunTracker.snapshot(context)
    val total = state.totals.values.sum()
    val payload =
        buildJsonObject {
            put("enabled", ToolUsageTracker.isStatsEnabled())
            put("totalRuns", total)
            put(
                "byOutcome",
                buildJsonObject {
                    state.totals.entries.sortedByDescending { it.value }.forEach { (k, v) -> put(k, v) }
                },
            )
            put(
                "byModel",
                buildJsonArray {
                    state.byModel.entries.sortedByDescending { e -> e.value.values.sum() }.forEach { (model, counts) ->
                        add(
                            buildJsonObject {
                                put("modelId", model)
                                put("runs", counts.values.sum())
                                put(
                                    "byOutcome",
                                    buildJsonObject {
                                        counts.entries.sortedByDescending { it.value }.forEach { (k, v) -> put(k, v) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("totalCost", state.totalCost)
            put(
                "byProviderModel",
                buildJsonArray {
                    state.byProviderModel.entries.sortedByDescending { e -> e.value.runs }.forEach { (key, stats) ->
                        add(
                            buildJsonObject {
                                put("key", key)
                                stats.providerName?.let { put("providerName", it) }
                                stats.modelDisplayName?.let { put("modelDisplayName", it) }
                                put("runs", stats.runs)
                                put("promptTokens", stats.promptTokens)
                                put("completionTokens", stats.completionTokens)
                                put("cachedTokens", stats.cachedTokens)
                                // 费用只含上报部分；costReportedRuns=0 表示该组合费用"未上报"，不估算
                                put("cost", stats.cost)
                                put("costReportedRuns", stats.costReportedRuns)
                            },
                        )
                    }
                },
            )
            put("avgDurationMs", if (total > 0) state.totalDurationMs / total else 0)
            put(
                "timeSplit",
                buildJsonObject {
                    put("modelMs", state.totalModelMs)
                    put("toolMs", state.totalToolMs)
                    put("totalMs", state.totalDurationMs)
                    put(
                        "otherMs",
                        (state.totalDurationMs - state.totalModelMs - state.totalToolMs).coerceAtLeast(0),
                    )
                    put("note", "耗时分解 = 模型请求 + 工具执行 + 其余；otherMs 含审批等待、排队、用户输入等。")
                },
            )
            put(
                "tokens",
                buildJsonObject {
                    put("prompt", state.totalPromptTokens)
                    put("completion", state.totalCompletionTokens)
                    put("cached", state.totalCachedTokens)
                },
            )
            put(
                "recent",
                buildJsonArray {
                    state.recent.takeLast(limit).reversed().forEach { run ->
                        add(
                            buildJsonObject {
                                put("ts", run.ts)
                                run.conversationId?.let { put("conversationId", it) }
                                run.modelId?.let { put("modelId", it) }
                                put("outcome", run.outcome)
                                run.finishReason?.let { put("finishReason", it) }
                                run.errorKind?.let { put("errorKind", it) }
                                put("durationMs", run.durationMs)
                                put("modelMs", run.modelMs)
                                put("toolMs", run.toolMs)
                                put("steps", run.steps)
                                put("promptTokens", run.promptTokens)
                                put("completionTokens", run.completionTokens)
                                put("cachedTokens", run.cachedTokens)
                            },
                        )
                    }
                },
            )
            put(
                "hint",
                "每次生成一条归因记录（COMPLETED / USER_CANCELLED / TIMEOUT / LOOP_GUARD / NETWORK_ERROR / " +
                    "RATE_LIMIT / API_ERROR / UNKNOWN）；按 outcome、modelId 与 provider×模型 聚合，recent 为最近明细。" +
                    "token 为**按请求累加**（与平台账单同口径：多步请求各按完整 prompt 计），cached 已夹在 " +
                    "prompt 之内。cost 只含 provider 上报的部分（未上报的不估算）。只记枚举与计数，不含消息内容；reset=true 可清空。",
            )
        }
    return payload.toString()
}

// ---------- tool_usage_stats ----------

/**
 * 本地工具的调用统计（次数/失败数/平均耗时/最近调用时间），可 reset 清零。
 * 只记录工具名与计数，不含任何参数。与 tool_surface_report 对照可得出"从未使用"清单。
 *
 * 口径：常规工具在**执行完成**时由装配层统一埋点；交互式工具（如 `ask_user`）没有可执行体，
 * 它的"执行"就是用户的回答，由 ChatService 在回答落库时补记一次（无执行耗时，不计失败）。
 */
internal suspend fun usageStatsPayload(
    context: Context,
    settingsStore: SettingsStore,
    params: JsonObject,
): String {
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 200)
            val reset = params["reset"]?.jsonPrimitive?.booleanOrNull ?: false
            if (reset) ToolUsageTracker.clear(context)
            val snapshot = ToolUsageTracker.snapshot(context)
            val injected = ToolUsageTracker.injectedNames(context)
            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.getCurrentAssistant()
            val extraCold = assistant.extraColdTools.toSet()
            val minCalls = (params["min_calls"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 100_000)
            val payload =
                buildJsonObject {
                    put("assistant", assistant.name)
                    // 统计开关的**当前生效值**（进程内由装配同步）：关时下面的数据只是历史存量、不再增长。
                    put("toolStatsEnabled", ToolUsageTracker.isStatsEnabled())
                    put("trackedToolCount", snapshot.size)
                    put("adviceThreshold", minCalls)
                    put("totalCalls", snapshot.sumOf { it.count })
                    put("resetApplied", reset)
                    put(
                        "top",
                        buildJsonArray {
                            snapshot.take(limit).forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("name", entry.name)
                                        // 是否已注入给模型（最近一次装配结果）——「开了没用」一眼可见。
                                        put("enabled", entry.name in injected)
                                        put("count", entry.count)
                                        put("failures", entry.failures)
                                        put("avgMs", entry.avgMs)
                                        put("lastUsedAt", entry.lastUsedAt)
                                        // 失败原因分布（异常类名 → 次数）；从未失败就不占字段。
                                        if (entry.failureKinds.isNotEmpty()) {
                                            put(
                                                "failureKinds",
                                                buildJsonObject {
                                                    entry.failureKinds.entries
                                                        .sortedByDescending { it.value }
                                                        .forEach { (kind, n) -> put(kind, n) }
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        },
                    )
                    put("injectedToolCount", injected.size)
                    put(
                        "enabledButNeverCalled",
                        buildJsonArray {
                            injected
                                .filter { name -> snapshot.none { it.name == name } }
                                .sorted()
                                .forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    // 注入集哈希：与上次不同 = 这轮之后的前缀缓存会失效（解释“为什么突然全价”）。
                    ToolUsageTracker.surfaceHashState(context)?.let { (hash, changes) ->
                        put(
                            "surfaceHash",
                            buildJsonObject {
                                put("current", hash)
                                put("totalChanges", changes)
                            },
                        )
                    }
                    // 解锁统计：与调用次数对照，判断降温名单配得对不对。
                    val unlocks = ToolUsageTracker.unlockCounts(context)
                    if (unlocks.isNotEmpty()) {
                        put(
                            "unlockStats",
                            buildJsonArray {
                                unlocks.entries
                                    .sortedByDescending { it.value }
                                    .take(20)
                                    .forEach { (name, unlockCount) ->
                                        val calls = snapshot.firstOrNull { it.name == name }?.count ?: 0
                                        add(
                                            buildJsonObject {
                                                put("name", name)
                                                put("unlocks", unlockCount)
                                                put("calls", calls)
                                                put(
                                                    "verdict",
                                                    if (calls > 0) "used_after_unlock" else "unlocked_but_never_called",
                                                )
                                            },
                                        )
                                    }
                            },
                        )
                    }
                    put("advice", usageAdviceJson(snapshot, extraCold, minCalls))
                    put(
                        "hint",
                        "enabledButNeverCalled = 已注入给模型但从未被调用的工具；injectedToolCount 为最近一次注入的工具总数。" +
                            "advice = 可操作建议（按「调用次数 × 生效档位」算）：remove_from_extra_cold 表示该工具高频却在助手级降温名单里，" +
                            "每次会话首用都要先 get_tool_schema 解锁，建议移出（adviceThreshold 为判定阈值，可用 min_calls 覆盖）。",
                    )
                }
    return payload.toString()
}

/**
 * 依据「调用次数 × 生效档位」给出**只读**建议。
 *
 * 目前只报一类：**高频却落在助手级降温名单**（extraColdTools）的工具 —— 它们每次会话首次调用
 * 都要先走一次 `get_tool_schema` 解锁，比留在热/温档更贵，属"名单配错了"而非"策略如此"。
 * 阈值默认 10 次，可用 `min_calls` 覆盖。
 */
private fun usageAdviceJson(
    snapshot: List<ToolUsageTracker.Entry>,
    extraCold: Set<String>,
    minCalls: Int,
): JsonArray =
    buildJsonArray {
        snapshot
            .filter { entry ->
                entry.count >= minCalls &&
                    ToolSurfacePolicy.decide(entry.name, extraCold).source == TierSource.ASSISTANT_EXTRA_COLD
            }
            .sortedByDescending { it.count }
            .forEach { entry ->
                add(
                    buildJsonObject {
                        put("name", entry.name)
                        put("count", entry.count)
                        put("tier", SurfaceTier.COLD.name)
                        put("tierSource", TierSource.ASSISTANT_EXTRA_COLD.name)
                        put("suggestion", "remove_from_extra_cold")
                        put(
                            "reason",
                            "调用 ${entry.count} 次（≥ 阈值 $minCalls）却生效为 COLD：每次会话首用需先 get_tool_schema 解锁，" +
                                "成本高于留在热/温档，建议在助手设置里移出 extraColdTools。",
                        )
                    },
                )
            }
    }

// ---------- tool_scope ----------

/**
 * 当前助手的**工具面视图**（只读）：本地工具选项 / 白名单 / 助手级降温名单，
 * 以及每个工具实际生效的档位与**档位来源**，并标注白名单模式下不可移除的保命工具。
 *
 * 口径与来源：
 * - 工具清单 = 最近一次装配出的注入集合（[ToolUsageTracker.injectedNames]），与模型实际看到的一致；
 *   白名单**同时是执行边界**（执行按注入列表查工具），故这就是“能调什么”的真实快照。
 * - 档位/来源 = [ToolSurfacePolicy.decide]（判据与装配共用，避免两套逻辑漂移）。
 * - 保命工具 = [ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES]：零副作用的自救层
 *   （求援 / 列工具 / 取参数表），白名单模式下始终注入，不随名单收窄而消失。
 * - 只读：不修改任何配置。
 */
// 只读快照：字段多但**线性直读**（拆分反而割裂“一次看全”这个用途）；detekt 的长度/复杂度在此豁免。
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun toolScopePayload(
    context: Context,
    settingsStore: SettingsStore,
    params: JsonObject,
): String {
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.getCurrentAssistant()
    val onlyTools = assistant.onlyTools
    val extraCold = assistant.extraColdTools.toSet()
    val injected = ToolUsageTracker.injectedNames(context)
    val trimEnabled = settings.displaySetting.toolSurfaceTrimming && ToolSurfacePolicy.TRIM_ENABLED
    val tierFilter = params["tier"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 200).coerceIn(1, 500)

    data class Row(val name: String, val tier: SurfaceTier, val source: String, val alwaysKept: Boolean)

    val alwaysKeptToolNames = ToolSurfacePolicy.ALWAYS_KEEP_TOOL_NAMES
    // 白名单 + 裁剪同时开启 = “白名单当中间档”模式：名单外降冷水（而非硬删）。
    // 判据必须与实际装配一致（同一函数），否则“诊断所见”又会与“装配实际”漂移。
    val scopeAsTier = trimEnabled && onlyTools.isNotEmpty()
    val rows =
        injected.sorted().map { name ->
            val decision =
                if (scopeAsTier) {
                    ToolSurfacePolicy.tierWithScope(name, onlyTools, extraCold)
                } else {
                    ToolSurfacePolicy.decide(name, extraCold)
                }
            Row(name, decision.tier, decision.source.name, name in alwaysKeptToolNames)
        }
    val visible = if (tierFilter == null) rows else rows.filter { it.tier.name == tierFilter }

    // 名单校验：四份助手级名单都是**精确名匹配**，写错一个字符就静默不生效。
    // 这里把「未匹配到任何已注入工具」的项显式列出（工具全集 = injected），供自查。
    val knownNames = injected.toSet()
    val unmatchedLists =
        buildJsonObject {
            fun checkList(key: String, names: List<String>) {
                val missing = names.filter { it !in knownNames }
                if (missing.isNotEmpty()) put(key, JsonArray(missing.map { JsonPrimitive(it) }))
            }
            checkList("extra_cold", assistant.extraColdTools)
            checkList("only_tools", assistant.onlyTools)
            checkList("compact_output", assistant.toolOutputCompactTools)
            checkList("digest_output", assistant.toolOutputDigestTools)
        }
    val payload =
        buildJsonObject {
            put("assistant", assistant.name)
            put("trimEnabled", trimEnabled)
            put("unmatched_lists", unmatchedLists)
            // 常驻内容账本：每轮固定成本的构成（只记长度；token 为 chars/3 估算）
            ContextLedger.last(context)?.let { ledger ->
                put(
                    "fixedCost",
                    buildJsonObject {
                        put("stable", ledger.stable)
                        put("volatile", ledger.volatile)
                        put("totalChars", ledger.totalChars)
                        put("estTokens", ledger.estTokens)
                        put("toolCount", ledger.toolCount)
                        put("atMs", ledger.atMs)
                        put(
                            "breakdown",
                            buildJsonObject {
                                put("assistantPrompt", ledger.assistantPrompt)
                                put("toolPrompts", ledger.toolPrompts)
                                put("memory", ledger.memory)
                                put("recentChats", ledger.recentChats)
                                put("addendum", ledger.addendum)
                            },
                        )
                        put(
                            "hint",
                            "字符 → token 为 chars/3 估算（与工具面报告同口径）；toolPrompts 与工具面的工具描述有语义重叠，别重复计算。",
                        )
                    },
                )
            }
            put("toolCount", rows.size)
            put(
                "counts",
                buildJsonObject {
                    SurfaceTier.values().forEach { tier ->
                        put(tier.name.lowercase(), rows.count { it.tier == tier })
                    }
                },
            )
            put(
                "tools",
                buildJsonArray {
                    visible.take(limit).forEach { row ->
                        add(
                            buildJsonObject {
                                put("name", row.name)
                                put("tier", row.tier.name)
                                put("tierSource", row.source)
                                put("alwaysKept", row.alwaysKept)
                            },
                        )
                    }
                },
            )
            if (visible.size > limit) put("truncated", visible.size - limit)
            put(
                "localToolOptions",
                buildJsonObject {
                    LocalToolCatalog.groups().forEach { (category, options) ->
                        val names = options.filter { it in assistant.localTools }.map { it.toString() }
                        if (names.isNotEmpty()) {
                            put(category.id, buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                        }
                    }
                },
            )
            put("onlyTools", buildJsonArray { onlyTools.forEach { add(JsonPrimitive(it)) } })
            put("extraColdTools", buildJsonArray { extraCold.sorted().forEach { add(JsonPrimitive(it)) } })
            put("alwaysKeptTools", buildJsonArray { alwaysKeptToolNames.sorted().forEach { add(JsonPrimitive(it)) } })
            put(
                "onlyToolsNotInjected",
                buildJsonArray {
                    onlyTools.filterNot { it in injected }.sorted().forEach { add(JsonPrimitive(it)) }
                },
            )
            // 选项级范围：哪些**没启用**、为什么。与装配共用判据（LocalToolCatalog.capabilityOf +
            // ToolCapabilities.satisfies），避开“诊断说能用、实际没注入”这类漂移。
            put(
                "optionScope",
                buildJsonObject {
                    val capabilities = ToolCapabilities.of(context)
                    val selected = assistant.localTools.toSet()
                    val disabled =
                        LocalToolCatalog.all.mapNotNull { option ->
                            val reason =
                                when {
                                    option !in selected -> "not_selected"
                                    !capabilities.satisfies(LocalToolCatalog.capabilityOf(option)) ->
                                        "capability_unavailable"
                                    else -> null
                                }
                            reason?.let { option to it }
                        }
                    put("total", LocalToolCatalog.all.size)
                    put("enabled", LocalToolCatalog.all.size - disabled.size)
                    put("disabledCount", disabled.size)
                    put(
                        "disabled",
                        buildJsonArray {
                            disabled.forEach { (option, reason) ->
                                add(
                                    buildJsonObject {
                                        put("option", option::class.simpleName ?: option.toString())
                                        put("reason", reason)
                                        put("capability", LocalToolCatalog.capabilityOf(option).name)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "capabilityReady",
                        buildJsonObject {
                            put("shizuku", capabilities.shizukuReady)
                            put("accessibility", capabilities.accessibilityReady)
                            put("termux", capabilities.termuxInstalled)
                        },
                    )
                },
            )
            // 渲染覆盖自检：注册表 key 数 / 与当前注入集匹配数 / 注册了但当前注入集里没有的 key。
            // unmatchedKeys 两种成因（只读诊断不猜）：该工具当前未启用（或能力未就绪），
            // 或名字对不上（死键）—— 静态核实用 `工具/tool-inventory.sh --check`。
            val uiKeys = ToolUIRegistry.registeredKeys
            val injectedSet = injected.toSet()
            val usageSnapshot = ToolUsageTracker.snapshot(context)
            put(
                "uiCoverage",
                buildJsonObject {
                    put("registryKeys", uiKeys.size)
                    put("matchedInjected", uiKeys.count { it in injectedSet })
                    put(
                        "unmatchedKeys",
                        buildJsonArray {
                            uiKeys.filterNot { it in injectedSet }.sorted().forEach { add(JsonPrimitive(it)) }
                        },
                    )
                    put("injectedWithoutUi", injectedSet.count { it !in uiKeys })
                    if (ToolUsageTracker.isStatsEnabled()) {
                        put("usageTracked", usageSnapshot.size)
                        put("injectedZeroCall", injectedSet.count { name -> usageSnapshot.none { it.name == name } })
                    } else {
                        put("usageNote", "toolStatsEnabled=false：无调用统计，零调用数不可用")
                    }
                },
            )
            put(
                "hint",
                "只读快照。tools 来自最近一次装配的注入集合（白名单同时是执行边界：名单外的工具既不可见也不可调用）。" +
                    "tierSource: ASSISTANT_EXTRA_COLD=助手级降温 / POLICY_HOT=策略热档 / POLICY_COLD_EXTRA=策略冷档单件 / " +
                    "POLICY_COLD_PREFIX=策略冷档家族前缀 / DEFAULT_WARM=默认温档。alwaysKept 的三条是零副作用自救层" +
                    "（求援 / 列工具 / 取参数表），白名单模式下不可移除；onlyToolsNotInjected = 白名单里未出现在当前注入集的项" +
                    "（拼错或与其它限制冲突）；trimEnabled=false 时档位不改变注入形态。" +
                    "optionScope = 选项级启用情况：total/enabled/disabledCount + 逐项原因（not_selected=未勾选 / " +
                    "capability_unavailable=能力未就绪），capabilityReady 为 Shizuku/无障碍/Termux 探测结果。",
            )
        }
    return payload.toString()
}
