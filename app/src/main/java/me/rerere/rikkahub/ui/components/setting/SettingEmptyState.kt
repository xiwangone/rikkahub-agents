package me.rerere.rikkahub.ui.components.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.UsageRange

/**
 * 设置页共享空态 / 计数状态。
 *
 * 各页原来内联写的「空时置灰 / 空时给提示 / 计数为 null 时显示计算中」收拢于此，
 * 同一映射不再两处各写一遍。
 */

/** 文件清理抽屉「删除」按钮启用条件：有可清理项才可点。 */
internal fun cleanDeleteButtonEnabled(count: Int): Boolean = count > 0

/** SSH 主机编辑弹窗密钥选择区两组入口的启用与提示可见性。 */
internal data class SshKeyChoiceAvailability(
    val templateEnabled: Boolean,
    val templateHintVisible: Boolean,
    val vaultPickEnabled: Boolean,
    val vaultHintVisible: Boolean,
)

/**
 * SSH 主机编辑弹窗密钥选择区：「从模板新建」与「从凭证库选择」。
 * 两组入口始终显示，空库时置灰并在下方给提示，否则用户根本不知道有这功能。
 */
internal fun sshKeyChoiceAvailability(
    hasTemplates: Boolean,
    hasKeys: Boolean,
): SshKeyChoiceAvailability = SshKeyChoiceAvailability(
    templateEnabled = hasTemplates,
    templateHintVisible = !hasTemplates,
    vaultPickEnabled = hasKeys,
    vaultHintVisible = !hasKeys,
)

/**
 * 用量统计范围的文案资源：页面空态文案与范围 Chip 选项列表共用同一映射。
 * 枚举声明顺序（TODAY、LAST_7_DAYS、LAST_30_DAYS、ALL_TIME）即 Chip 顺序。
 */
@StringRes
internal fun usageRangeLabelRes(range: UsageRange): Int = when (range) {
    UsageRange.TODAY -> R.string.usage_stats_range_today
    UsageRange.LAST_7_DAYS -> R.string.usage_stats_range_7d
    UsageRange.LAST_30_DAYS -> R.string.usage_stats_range_30d
    UsageRange.ALL_TIME -> R.string.usage_stats_range_all
}

/** 异步计数状态：`null` = 计算中。 */
internal sealed interface CountState {
    data object Loading : CountState
    data class Ready(val count: Int) : CountState
}

/** 凭证库计数（`null` = 计算中）转异步计数状态。 */
internal fun Int?.toCountState(): CountState =
    if (this == null) CountState.Loading else CountState.Ready(this)
