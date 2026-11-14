package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.repairToolPairing
import me.rerere.rikkahub.data.ai.INTERRUPTED_UNKNOWN_OUTCOME_REASON
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard

/**
 * 发送前修复「未执行工具」造成的协议破损。
 *
 * 背景：OpenAI 兼容协议要求带 `tool_calls` 的助手消息后面必须有对应的工具响应。若历史里留着
 * **未执行**（结果为空）的工具调用 —— 例如结果被删除、或生成被中断后留下的半成品 —— 服务端会
 * 直接拒收整个请求，并在每轮重复报同一句结构错误，表现为该会话「一直报错」、只能新建会话绕过。
 *
 * 修法：复用 [repairToolPairing]（内部就是 [me.rerere.ai.ui.migrateToolMessages] +
 * [me.rerere.ai.ui.abandonUnexecutedTools]，与生成前清理**同一判据**，避免两套逻辑漂移）。补桩内容里写明原因，
 * 模型读到后能知道「这里曾有一次没有结果的调用」，不会误以为什么都没发生而重复执行。
 *
 * 理由按「执行是否开始过」分叉：从未开始的工具写 [REASON]（"未执行"是事实）；开始过但结果没
 * 回来的写与重放安全通道、生成前清理同文的 [INTERRUPTED_UNKNOWN_OUTCOME_REASON]（"状态未知、
 * 先核实"）。已是终态 Denied 的工具保留原理由、只补拒绝信封（硬线拦截去标记后走
 * `hardline_blocked`，其余走 `permission_denied`）。三处必须同文：本 transformer 在请求装配时跑在重放通道之后，补桩会合成 output 并
 * 覆盖 approvalState —— 若此处不分叉，重放通道的定案会被"单纯缺结果"覆盖，模型读到后可能
 * 重跑副作用已发生的工具（重复发送、重复提交、重复计费）。
 *
 * 两点约束：
 *  1. **只改发送出去的副本**：transformer 的结果仅用于构造请求，不回写会话与落库 —— 消息本体与
 *     界面展示保持原样，不为「能发出去」而篡改用户历史；
 *  2. 无问题时各步函数都返回自身（引用相等），不产生额外开销。
 */
object ToolPairingRepairTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.repairToolPairing(
        REASON,
        INTERRUPTED_UNKNOWN_OUTCOME_REASON,
        deniedReasonForModel = HardlineCommandGuard::modelFacingReason,
    )

    private const val REASON = "tool result missing at request time"
}
