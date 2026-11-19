package me.rerere.rikkahub.ui.pages.extensions.workspace

import com.termux.terminal.TerminalTransport

/**
 * 给 transport 包一层启动描述：TerminalSession.initializeEmulator 会把
 * describeStart() 的返回值以 [文本] 的形式写到终端首行。远程 SSH 用它显示
 * [Connecting to ...]，本机 proot shell 默认不打印任何东西，用它补一句欢迎文本。
 */
internal class WelcomeTerminalTransport(
    private val delegate: TerminalTransport,
    private val welcomeText: String,
) : TerminalTransport by delegate {
    override fun describeStart(): String = welcomeText
}
