package com.termux.terminal;

/**
 * 本模块对应的上游 termux-app 版本。
 *
 * <p>为什么必须显式写在这里：{@code com.termux.terminal.*} 由本模块（仓库内源码）提供，
 * 而 {@code com.termux.view.*}（TerminalView）来自外部 AAR
 * {@code com.termux.termux-app:terminal-view}。两者的 API 必须同版本——
 * AAR 是已编译类，它调用本模块的方法时**编译期不做校验**，版本错配只会在运行时
 * 抛 {@code NoSuchMethodError}（实例：v0.119.0-beta.3 的 TerminalView 调用
 * {@code TerminalSession.updateSize(IIII)}，而本模块只提供两参数重载 → 打开终端页即崩）。
 *
 * <p>约束由 {@code 工具/termux-module-lint.sh} 在 {@code repo.sh check} 里强制：
 * 升级 {@code gradle/libs.versions.toml} 的 {@code termux} 版本时，
 * 必须同步升级本模块源码并更新这里的值，否则检查器直接阻断。
 */
public final class TerminalModuleVersion {

    /** 与 {@code gradle/libs.versions.toml} 的 {@code termux} 声明保持一致。 */
    public static final String UPSTREAM_VERSION = "0.118.0";

    private TerminalModuleVersion() {}
}
