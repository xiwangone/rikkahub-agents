package me.rerere.rikkahub.data.ai.tools

/**
 * 工具面的"温度"分档：决定每个工具注入给模型时携带多少 schema。
 *
 * 采用「默认全量不区分 + 纯名单驱动」策略：
 * - 未命中任何名单时，所有已启用工具均为 [HOT]（保留完整 description 与参数表）；
 * - 命中用户配置的冷名单或白名单外时降为 [COLD]（仅保留一行用途 + 空 schema，
 *   需要时通过 `list_tools` / `get_tool_schema` 按会话解锁）。
 *
 * 注意：档位**只影响注入给模型的视图**，不影响工具能否被调用；也不会移除任何工具。
 */
enum class SurfaceTier {
    /** 正常态：保留完整 description 与参数表。 */
    HOT,

    /** 兼容保留枚举值；纯名单模式下不再对未配置工具做描述截断。 */
    WARM,

    /** 冷档：仅保留一行用途与空 schema，调用前需先用 `get_tool_schema` 取回完整参数。 */
    COLD,
}

/** 档位判定的来源：解释“这个工具为什么落在这一档”。 */
enum class TierSource {
    /** 命中助手级降温名单（助手级 extraColdTools 配置）。 */
    ASSISTANT_EXTRA_COLD,

    /** 落在助手级工具白名单之外（白名单 + 精简同时开启时，名单外降冷而非硬删）。 */
    OUTSIDE_ASSISTANT_SCOPE,

    /** 默认全量（未命中冷名单且在白名单内，或未配置名单）。 */
    DEFAULT_HOT,
}

/** 档位判定结果：档位 + 来源。 */
data class TierDecision(
    val tier: SurfaceTier,
    val source: TierSource,
)

/**
 * 纯名单驱动的档位判定。
 *
 * 不预设开发者的主观冷热表，也不做依赖当前界面的动态升降档，保证行为可预测且不打穿前缀缓存。
 */
object ToolSurfacePolicy {

    /**
     * 工具面裁剪总开关（与设置项 `DisplaySetting.toolSurfaceTrimming` 取**与**关系；后者默认关闭）。
     */
    const val TRIM_ENABLED = true

    /**
     * 白名单模式下的保命工具：**零副作用的自救层**（求援 / 列工具 / 取参数表），
     * 白名单收窄时始终注入，避免“看不见工具也取不回参数表”的死局。
     *
     * 单一来源：装配侧（ChatToolFactory 的范围过滤）与只读诊断（diagnostics kind=tool_scope）共用。
     * 只放**不产生副作用**的元工具——读写/执行类工具不得无条件进入本名单，避免绕过助手级白名单隔离。
     */
    val ALWAYS_KEEP_TOOL_NAMES: Set<String> =
        setOf("list_tools", "get_tool_schema", "ask_user")

    /**
     * 档位判定**含来源**：仅依据助手级冷名单 [extraCold]，未命中即返回 [SurfaceTier.HOT]。
     */
    fun decide(
        toolName: String,
        extraCold: Set<String> = emptySet(),
    ): TierDecision =
        if (toolName in extraCold) {
            TierDecision(SurfaceTier.COLD, TierSource.ASSISTANT_EXTRA_COLD)
        } else {
            TierDecision(SurfaceTier.HOT, TierSource.DEFAULT_HOT)
        }

    fun tierOf(
        toolName: String,
        extraCold: Set<String> = emptySet(),
    ): SurfaceTier = decide(toolName, extraCold).tier

    /**
     * 带**助手级白名单**的档位判定（把白名单当“中间档”用）。
     *
     * 语义：`scope` 非空时，名单内工具照常判档，**名单外一律冷档** —— 与“硬过滤”的区别在于
     * 工具**仍在注入列表里**（`list_tools` 列得到、`get_tool_schema` 查得到），只是默认只发一行
     * 用途 + 空 schema，需要时当会话解锁。保命工具不受名单影响。
     */
    fun tierWithScope(
        toolName: String,
        scope: List<String>,
        extraCold: Set<String> = emptySet(),
    ): TierDecision =
        when {
            scope.isEmpty() -> decide(toolName, extraCold)
            toolName in scope -> decide(toolName, extraCold)
            toolName in ALWAYS_KEEP_TOOL_NAMES -> decide(toolName, extraCold)
            else -> TierDecision(SurfaceTier.COLD, TierSource.OUTSIDE_ASSISTANT_SCOPE)
        }

    fun tierOfWithScope(
        toolName: String,
        scope: List<String>,
        extraCold: Set<String> = emptySet(),
    ): SurfaceTier = tierWithScope(toolName, scope, extraCold).tier
}
