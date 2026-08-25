package me.rerere.ai.provider.providers.reasonix

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.AskQuestion

/**
 * Reasonix 富事件（工具审批 / 提问卡片）的交互回调。
 *
 * 默认 [NOOP] 只做展示不交互；App 侧实现为通知/对话框交互桥，在用户应答后
 * 自行调用 [ReasonixApi.approve] / [ReasonixApi.answer] 把决策回传 serve。
 * 回调在 SSE 事件解析协程内执行，实现必须保持非阻塞（异步交互）。
 */
interface ReasonixInteractionHandler {
    /** serve 请求工具审批（approval_request 事件）。 */
    fun onApprovalRequest(setting: ProviderSetting.Reasonix, id: String, tool: String, subject: String?)

    /** serve 发起提问卡片（ask_request 事件）。 */
    fun onAskRequest(setting: ProviderSetting.Reasonix, id: String, questions: List<AskQuestion>)

    companion object {
        val NOOP = object : ReasonixInteractionHandler {
            override fun onApprovalRequest(
                setting: ProviderSetting.Reasonix,
                id: String,
                tool: String,
                subject: String?,
            ) = Unit

            override fun onAskRequest(
                setting: ProviderSetting.Reasonix,
                id: String,
                questions: List<AskQuestion>,
            ) = Unit
        }
    }
}