package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.log.AppLog

class Navigator(
    private val backStack: MutableList<NavKey>,
) {
    fun navigate(
        screen: Screen,
        builder: NavigateOptionsBuilder.() -> Unit = {},
    ) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(
        screen: Screen,
        builder: PopUpToBuilder.() -> Unit = {},
    ) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

/**
 * 应用级导航器。
 *
 * 悬浮窗等**独立 Compose 树**没有 RouteActivity 的 Provider；此前的默认值是
 * `error("No Navigator provided")`，一读到就崩（表现为「在其他应用点开悬浮窗闪退」，
 * 栈顶是 HighlightCodeBlock 读 LocalNavController）。
 * 这里改成安全的空实现兜底：在这些树里导航动作静默无效（预期降级），不再崩溃；
 * 同时打一条日志保留可诊断性。需要真实导航的入口仍应由 Provider 传入。
 */
val LocalNavController =
    compositionLocalOf<Navigator> {
        AppLog.w("NavContext", "LocalNavController read without a provider; using no-op fallback")
        Navigator(mutableListOf())
    }
