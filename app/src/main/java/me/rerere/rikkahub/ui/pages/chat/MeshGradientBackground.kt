package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 使用当前 Material 3 主题色的动态渐变背景。
 *
 * 原理:
 *  1. 底层一个线性渐变(顶部偏蓝、底部偏白)。
 *  2. 上面叠几个 radialGradient 光斑(中心有色 → 边缘透明),天生就是柔的。
 *  3. 每个光斑用一个独立周期的相位,沿正弦/余弦轨迹缓慢漂移。
 *
 * 不依赖 Modifier.blur,全 API 级别可用,性能也更好。
 *
 * 时钟节流:相位不再用 rememberInfiniteTransition(每个显示刷新率的 vsync 都重新计算,
 * 120Hz 屏幕上空闲时也是 120fps),而是用一个按 [FRAME_INTERVAL_MS] 限速的时间源推进,
 * 只在 Canvas 的绘制 lambda 里读取,这样状态更新只触发重绘,不触发重组。生命周期低于
 * STARTED(切后台、锁屏)时循环挂起,不再消耗 CPU。
 */
private const val FRAME_INTERVAL_MS = 33L // 约 30fps 上限;漂移很慢,肉眼看不出和更高帧率的差别

@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val elapsedMillis = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 从上次暂停处继续,回到前台时不会跳回初始画面
            val start = withInfiniteAnimationFrameMillis { it } - elapsedMillis.floatValue.toLong()
            while (isActive) {
                val now = withInfiniteAnimationFrameMillis { it }
                elapsedMillis.floatValue = (now - start).toFloat()
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val baseGradient =
        arrayOf(
            0.0f to colorScheme.surface,
            0.48f to colorScheme.background,
            1.0f to colorScheme.background,
        )

    // 以主题容器色构成低浓度光斑，适配动态色、预设色和暗色主题。
    val blobPrimary = colorScheme.primaryContainer
    val blobSecondary = colorScheme.secondaryContainer
    val blobTertiary = colorScheme.tertiaryContainer
    val blobAccent = colorScheme.primary
    val alphaPrimary = 0.38f
    val alphaSecondary = 0.32f
    val alphaTertiary = 0.34f
    val alphaAccent = 0.24f

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 只在绘制阶段读取时间状态,避免每次限速更新触发重组
            val t = elapsedMillis.floatValue
            val p1 = phaseAtElapsed(t, 5_500, loops = 20) // 1.15 对应 20 圈 (总长 110秒)
            val p2 = phaseAtElapsed(t, 7_000, loops = 1) // 1.0  对应 1 圈
            val p3 = phaseAtElapsed(t, 8_500, loops = 10) // 0.9  对应 10 圈 (总长 85秒)
            val p4 = phaseAtElapsed(t, 6_200, loops = 10) // 1.1  对应 10 圈 (总长 62秒)

            val w = size.width
            val h = size.height
            // 光斑半径取屏幕较长边的比例,够大才柔
            val r = maxOf(w, h)

            // 光斑全部聚在顶部,向下渐隐,保留下半屏留白
            // 主色光斑横向缓慢漂移
            drawBlob(
                center =
                    Offset(
                        w * 0.48f + sin(p1) * w * 0.38f,
                        h * 0.08f + cos(p1 * 1.15f) * h * 0.18f,
                    ),
                radius = r * 0.36f,
                color = blobPrimary,
                centerAlpha = alphaPrimary,
            )
            // 左上辅助色点缀
            drawBlob(
                center =
                    Offset(
                        w * 0.18f + sin(p2 + PI.toFloat() * 0.55f) * w * 0.30f,
                        h * 0.24f + cos(p2) * h * 0.20f,
                    ),
                radius = r * 0.28f,
                color = blobSecondary,
                centerAlpha = alphaSecondary,
            )
            // 右上第三主题色
            drawBlob(
                center =
                    Offset(
                        w * 0.82f + sin(p3 + PI.toFloat() * 0.9f) * w * -0.34f,
                        h * 0.12f + cos(p3 * 0.9f) * h * 0.18f,
                    ),
                radius = r * 0.30f,
                color = blobTertiary,
                centerAlpha = alphaTertiary,
            )
            // 低浓度主色光斑丰富层次，不压过消息文字
            drawBlob(
                center =
                    Offset(
                        w * 0.58f + sin(p4 + PI.toFloat() * 1.25f) * w * 0.28f,
                        h * 0.34f + cos(p4 * 1.1f) * h * 0.16f,
                    ),
                radius = r * 0.26f,
                color = blobAccent,
                centerAlpha = alphaAccent,
            )
        }

        content()
    }
}

/**
 * 计算某个相位在 [elapsedMillis] 时的角度,数学上等价于旧版
 * `infiniteRepeatable(tween(durationMillis * loops, easing = LinearEasing))`
 * (从 0 线性增长到 2π * loops,然后重启,即 RepeatMode.Restart):
 * 因为 2π * loops 正好是 2π 的整数倍,重启瞬间 sin/cos 的取值和上一帧连续,不会跳变;
 * 乘以非整数系数(如 1.15、0.9)时同理在整个周期末尾对齐回 0,不会有肉眼可见的跳动。
 */
internal fun phaseAtElapsed(elapsedMillis: Float, durationMillis: Int, loops: Int): Float {
    val totalMillis = durationMillis.toFloat() * loops
    val t = elapsedMillis.mod(totalMillis)
    return (t / durationMillis) * (2f * PI.toFloat())
}

/** 画一个柔光斑:中心有色,向外渐隐到透明。 */
private fun DrawScope.drawBlob(
    center: Offset,
    radius: Float,
    color: Color,
    centerAlpha: Float = 0.75f,
) {
    drawCircle(
        brush =
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = centerAlpha), Color.Transparent),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )
}
