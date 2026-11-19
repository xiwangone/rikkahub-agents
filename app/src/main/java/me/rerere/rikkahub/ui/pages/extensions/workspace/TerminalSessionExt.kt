package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.termux.terminal.TerminalSession

/** 终端回滚记录上限（行数）：SSH 主机页、远程工作区、本机工作区三处共用。 */
const val TERMINAL_TRANSCRIPT_ROWS = 2_000

internal fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}
