package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent
    val runInBackground: Boolean,
    val noResult: Boolean = false,   // #78/#79: suppress result text from encodeRun/parent notification
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /** 输入侧命中缓存的 token 数（命中率 = cached / in）；用于成本判读 */
    val tokensCached: Long = 0,
    val tripCount: Int = 0,
    /** 实际生效的工作区（会话级覆盖解析后）；null = 继承父助手 */
    val workspaceId: String? = null,
    /** 实际生效的工具白名单；null = 继承父助手（未收窄） */
    val toolScope: List<String>? = null,
    /** 本次派发是否属于“提权”（含写/执行类工具，或显式继承全量） */
    val elevated: Boolean = false,
    /** 生效工作区与父会话相同 → 写入可能互相覆盖（互踩风险可见化） */
    val sameWorkspaceAsParent: Boolean = false,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    const val DEFAULT_TIMEOUT_SECONDS = 300
    const val MAX_TIMEOUT_SECONDS = 1800
    const val DEFAULT_MAX_TRIPS = 12
    const val MAX_MAX_TRIPS = 30
    const val MAX_LABEL_LENGTH = 60
    const val GLOBAL_CONCURRENCY_CAP = 30
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 8
    const val REGISTRY_LRU_CAP = 50

    /**
     * 子代理**默认**工具集：未显式指定时的最小权限集（只读/调研）。
     *
     * 设计动机（2026-09-20 核实）：headless 会话的工具调用是**自动批准**的
     * （见 [me.rerere.rikkahub.data.ai.tools.HeadlessConversations] 的 auto-approve 集合），
     * 而子代理此前默认“继承父助手全量工具” → 派一个子代理等于交出「全量工具 + 免审批」。
     * 现在改为默认只给只读集；要写/执行，必须显式声明（dispatch 的 `tools`、或 profile 里
     * 写 `"*"` 表示继承父助手全量）。
     *
     * ⚠ 名字必须是与工具实际注册名**完全相等**的字符串（过滤是精确匹配，没有通配）。
     */
    val DEFAULT_SAFE_TOOL_SCOPE: List<String> = listOf(
        "workspace_read_file",
        "workspace_read_folder",
        "workspace_list",
        "workspace_search_code",
        "file_info",
        "read_file",
        "list_files",
        "find_files",
        "memory_search",
        "skill_get_content",
        "web_fetch",
        "subagent_list",
        "subagent_get",
    )

    /** 出现其中任一工具即视为“提权”派发（回显 elevated，让主对话知道写/执行已开放）。 */
    val ELEVATED_TOOL_NAMES: Set<String> = setOf(
        "workspace_write_file",
        "workspace_edit_file",
        "workspace_apply_edits",
        "workspace_create_folder",
        "workspace_shell",
        "workspace_run_background",
        "workspace_background_kill",
        "write_text_file",
        "termux_run_command",
        "ssh_exec",
        "ssh_exec_saved",
        "ssh_upload",
        "ssh_download",
    )

    /** 显式声明“继承父助手全量工具”的标记（逃生口）。 */
    const val TOOL_SCOPE_INHERIT_ALL = "*"

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Do NOT narrate progress step by step and do not emit progress lines. The parent
          only receives your FINAL message; intermediate narration is wasted tokens.
        - End with one short structured summary: what you did, what you found, and where any
          artifacts are (file paths). Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification - make the best judgment call you can
          and proceed.
    """.trimIndent()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    /**
     * #36: name of a configured [SubAgentProfile], resolved case-insensitively by
     * [SubAgentProfileResolver]. `modelId` above wins over the profile's model when both are
     * given; see [SubAgentEngine.executeRun].
     */
    val agentName: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    val noResult: Boolean = false,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    val label: String? = null,
    /**
     * 会话级工作区覆盖（工作区 uuid 字符串）。优先级：request.workspaceId > profile.workspaceId >
     * 父助手的工作区。子代理因此可以跑在与父对话隔离的工作区里（P45/P46 的隔离从惯例变成配置项）。
     */
    val workspaceId: String? = null,
)

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        return Result.Ok(request.copy(task = task))
    }
}

/**
 * #36: a named, reusable sub-agent configuration - a name, description, custom system
 * prompt and model, defined once in settings so the dispatching model can pick a specialist by
 * NAME instead of memorizing a model uuid. Resolved by [SubAgentProfileResolver]. `modelId` null
 * means the profile itself defers to the parent's model, mirroring the "null = inherit"
 * convention already used by [SubAgentRequest.modelId].
 */
@Serializable
data class SubAgentProfile(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: Uuid? = null,
    val enabled: Boolean = true,
    /** 该 profile 默认使用的工作区；null = 跟随父助手 */
    val workspaceId: Uuid? = null,
    /** 该 profile 默认的工具白名单（只减不增）；null/空 = 继承父助手全量 */
    val toolScope: List<String>? = null,
)
