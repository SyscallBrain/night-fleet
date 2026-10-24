package pt.tiago.nightfleet.ui.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** How hard the screen shakes: the own ship was hit (small) or sunk (large). */
enum class ShakeLevel(val amplitudeDp: Float, val durationMs: Int) {
    HIT(2f, 160),
    SUNK(5f, 260),
}

/** Largest offset a shake may reach, in dp. */
const val MAX_SHAKE_DP = 6f

/**
 * Shakes the content horizontally when [trigger] changes: a damped sine, [level] amplitude and duration.
 * Off with reduced motion. Each new trigger value starts a new shake.
 */
fun Modifier.screenShake(trigger: Any?, level: ShakeLevel): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current.density
    val progress = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger != null && !reduced) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(level.durationMs, easing = LinearEasing))
        }
    }
    val amplitude = level.amplitudeDp.coerceAtMost(MAX_SHAKE_DP) * density
    graphicsLayer {
        if (!reduced) {
            val t = progress.value
            translationX = amplitude * exp(-4.0 * t).toFloat() * sin(2.0 * PI * 5.0 * t).toFloat()
        }
    }
}
