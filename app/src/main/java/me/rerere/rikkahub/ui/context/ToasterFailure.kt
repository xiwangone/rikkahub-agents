package me.rerere.rikkahub.ui.context

import android.content.Context
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import me.rerere.rikkahub.data.ai.diagnoseFailure

/**
 * 统一的失败提示：把异常按原因归类（网络 / 鉴权 / 限流 / 服务端 / 模型不存在 / 上下文超限 …），
 * 分类标签走本地化资源 `error_kind_*`；服务端或库返回的**原始报错原文**附在末尾，
 * 保留排查线索。
 *
 * 各页面遇到「异常 → 提示」时统一调用本扩展，不再各自拼英文兜底文案。
 */
fun ToasterState.showFailure(
    context: Context,
    failure: Throwable,
    type: ToastType = ToastType.Error,
) {
    val diagnosis = diagnoseFailure(context, failure)
    show(
        message =
            if (diagnosis.raw.isNotBlank()) {
                "${diagnosis.label} — ${diagnosis.raw}"
            } else {
                diagnosis.label
            },
        type = type,
    )
}
