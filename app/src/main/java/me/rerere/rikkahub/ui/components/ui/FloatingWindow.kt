package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(window, visibility) {
        window?.setVisible(tag, visibility)
    }

    DisposableEffect(context, tag) {
        val control =
            FloatingX.install {
                setTag(tag)
                setContext(context)
                setGravity(FxGravity.LEFT_OR_BOTTOM)
                setOffsetXY(20f, -20f)
                setEnableAnimation(true)
                setLayoutView(
                    ComposeView(context).apply {
                        setContent {
                            RikkahubTheme {
                                content()
                            }
                        }
                    },
                )
            }
        window = control
        control.setVisible(tag, visibility)
        onDispose {
            control.cancel()
        }
    }
}

// FloatingX 2.3.7 exposes no cancellation-state property on IFxAppControl.
// A same-tag install cancels the previous handle and replaces it in this registry.
private fun IFxAppControl.setVisible(tag: String, visible: Boolean) {
    if (FloatingX.controlOrNull(tag) !== this) return
    if (visible) show() else hide()
}
