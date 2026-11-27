package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.AppIconLoadingStyle

@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val displaySetting = LocalSettings.current.displaySetting
    if (!displaySetting.useAppIconStyleLoadingIndicator) {
        ContainedLoadingIndicator(modifier = modifier)
        return
    }

    val preset = displaySetting.appIconPreset
    val transition = rememberInfiniteTransition()
    val tintColor = MaterialTheme.colorScheme.primary

    var rotationZ = 0f
    var scale = 1f
    var alpha = 1f
    var translationY = 0f

    when (preset.loadingStyle) {
        AppIconLoadingStyle.DIAMOND_ROTATE_PULSE -> {
            val rot by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
            val pulse by transition.animateFloat(
                initialValue = 0.88f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            rotationZ = rot
            scale = pulse
        }

        AppIconLoadingStyle.AURA_ORBIT_BREATHE -> {
            val rot by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
            val glow by transition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            rotationZ = rot
            alpha = glow
        }

        AppIconLoadingStyle.PRISM_BREATHE_SCALE -> {
            val breath by transition.animateFloat(
                initialValue = 0.84f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            val tilt by transition.animateFloat(
                initialValue = -10f,
                targetValue = 10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            scale = breath
            rotationZ = tilt
        }

        AppIconLoadingStyle.CHAT_FLOAT_ROCK -> {
            val bob by transition.animateFloat(
                initialValue = -3.5f,
                targetValue = 3.5f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            val rock by transition.animateFloat(
                initialValue = -7f,
                targetValue = 7f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            translationY = bob
            rotationZ = rock
        }

        AppIconLoadingStyle.STAR_TWINKLE_SPIN -> {
            val spin by transition.animateFloat(
                initialValue = 0f,
                targetValue = 180f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
            val twinkle by transition.animateFloat(
                initialValue = 0.78f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            rotationZ = spin
            scale = twinkle
        }

        AppIconLoadingStyle.TERMINAL_CURSOR_BLINK -> {
            val blink by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1000
                        1f at 0
                        1f at 460
                        0.28f at 500
                        0.28f at 920
                        1f at 1000
                    },
                    repeatMode = RepeatMode.Restart,
                ),
            )
            alpha = blink
        }

        AppIconLoadingStyle.ORBITAL_SPIN -> {
            val spin by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
            val glow by transition.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            rotationZ = spin
            alpha = glow
        }

        AppIconLoadingStyle.RABBIT_HOP_TILT -> {
            val hop by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5.5f at 260 using FastOutSlowInEasing
                        0f at 540 using FastOutSlowInEasing
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                ),
            )
            val tilt by transition.animateFloat(
                initialValue = -8f,
                targetValue = 8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
            translationY = hop
            rotationZ = tilt
        }
    }

    Image(
        painter = painterResource(preset.artwork.monoLayerRes),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tintColor),
        modifier = modifier.graphicsLayer {
            this.rotationZ = rotationZ
            this.scaleX = scale
            this.scaleY = scale
            this.alpha = alpha
            this.translationY = translationY
        },
    )
}
