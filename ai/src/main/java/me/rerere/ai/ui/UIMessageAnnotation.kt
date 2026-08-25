package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()

    /** 阶段指示标签（reasoning/planning/coding…）。 */
    @Serializable
    @SerialName("phase_indicator")
    data class PhaseIndicator(
        val label: String,
    ) : UIMessageAnnotation()

    /** 服务端系统级通知（用于渲染 Notice 条）。 */
    @Serializable
    @SerialName("notice")
    data class Notice(
        val text: String,
        val level: String? = null,
    ) : UIMessageAnnotation()

    /** 上下文压缩通知条。 */
    @Serializable
    @SerialName("compaction_notice")
    data class CompactionNotice(
        val trigger: String? = null,
    ) : UIMessageAnnotation()

    /** 服务端审批请求卡片（展示态；交互回传依赖 serve 应答协议，后续阶段接入）。 */
    @Serializable
    @SerialName("approval_request")
    data class ApprovalRequest(
        val id: String,
        val tool: String = "",
        val subject: String? = null,
    ) : UIMessageAnnotation()

    /** 服务端提问卡片（AskCard，展示态；作答回传依赖 serve 应答协议，后续阶段接入）。 */
    @Serializable
    @SerialName("ask_request")
    data class AskRequest(
        val id: String,
        val questions: List<AskQuestion> = emptyList(),
    ) : UIMessageAnnotation()
}
