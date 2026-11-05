package me.rerere.rikkahub.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.core.view.WindowCompat
import me.rerere.rikkahub.ui.context.LocalActivityResultRegistryOrNull
import me.rerere.rikkahub.utils.getActivity
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberCurrentColorMode
import me.rerere.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color.Black

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit,
) {
    val settings by rememberUserSettingsState()

    val darkTheme =
        when (colorMode) {
            ColorMode.SYSTEM -> isSystemInDarkTheme()
            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme =
        when {
            settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            else -> {
                val theme =
                    findThemeById(settings.themeId, settings.customThemes)
                        ?: findPresetTheme(settings.themeId)
                theme.getColorScheme(dark = darkTheme)
            }
        }
    val colorSchemeConverted =
        remember(darkTheme, amoledDarkMode, colorScheme) {
            if (darkTheme && amoledDarkMode) {
                colorScheme.copy(
                    background = AMOLED_DARK_BACKGROUND,
                    surface = AMOLED_DARK_BACKGROUND,
                )
            } else {
                colorScheme
            }
        }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors

    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme) {
            // 悬浮窗等独立界面的 context 不是 Activity：跳过系统栏处理，避免类型转换失败
            val window = view.context.getActivity()?.window
            if (window == null) {
                return@DisposableEffect onDispose { }
            }
            val controller = WindowCompat.getInsetsController(window, view)
            val previousLightStatusBars = controller.isAppearanceLightStatusBars
            val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
            controller.apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose {
                // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色。
                controller.isAppearanceLightStatusBars = previousLightStatusBars
                controller.isAppearanceLightNavigationBars = previousLightNavigationBars
            }
        }
    }

    // 独立界面的 Compose 树（浏览器页、安全模式等）也要能创建结果启动器：
    // 从当前 context 回溯宿主 Activity 并提供给整棵树；悬浮窗等无 Activity 时保持 null（走空实现）。
    val resultRegistryOwner = LocalContext.current.getActivity() as? ActivityResultRegistryOwner
    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColors,
        LocalOverscrollFactory provides null,
        LocalActivityResultRegistryOrNull provides resultRegistryOwner,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive(),
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current
