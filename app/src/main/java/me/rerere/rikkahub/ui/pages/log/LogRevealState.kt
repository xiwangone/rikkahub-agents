package me.rerere.rikkahub.ui.pages.log

import me.rerere.rikkahub.utils.LogRedactor

/**
 * 日志查看页单条显隐状态：按条目首现时间戳标识（重复累加不改变时间戳，刷新不漂移）。
 *
 * 默认全部未显隐；点眼睛按时间戳逐条切换；清空日志后丢弃重建。
 * 本身是不可变快照：[toggle] 与 [clear] 返回新实例，调用方整体替换引用
 * 以触发 Compose 重组（原地改集合不会重组）。
 */
class LogRevealState(private val revealed: Set<Long> = emptySet()) {

    /** 该条是否已点眼睛展开原文。 */
    fun isRevealed(timestamp: Long): Boolean = timestamp in revealed

    /** 切换一条的显隐，返回新状态。 */
    fun toggle(timestamp: Long): LogRevealState =
        if (timestamp in revealed) {
            LogRevealState(revealed - timestamp)
        } else {
            LogRevealState(revealed + timestamp)
        }

    /** 清空全部显隐，返回空状态。 */
    fun clear(): LogRevealState = LogRevealState()
}

/**
 * 屏内显示文本：未显隐显示脱敏文本，显隐显示原文。
 * 复制/分享出口不走这里，仍用脱敏的 formatEntry。
 * 调用方应对（文本，显隐）做 remember 缓存，避免滚动重组重复跑脱敏正则。
 */
fun displayLogText(rawText: String, revealed: Boolean): String =
    if (revealed) rawText else LogRedactor.maskText(rawText)
