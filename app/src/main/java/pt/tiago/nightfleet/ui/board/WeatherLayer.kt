package pt.tiago.nightfleet.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Ambient weather of a sea. Drawn behind the board, never over the cells. */
enum class WeatherKind { CALM, RAIN, FOG, AURORA, SNOW, STORM, ALGAE, STARS, DEEP, GULLS, MOON }

/** One ambient particle, positions and speeds in fractions of the layer size. */
data class WeatherParticle(val x: Float, val y: Float, val speed: Float, val size: Float, val phase: Float)

/** Seeded, deterministic weather particles (at most [MAX_WEATHER_PARTICLES]). */
object WeatherParticles {
    const val MAX_WEATHER_PARTICLES = 60

    fun generate(kind: WeatherKind, seed: Long = kind.ordinal.toLong(), count: Int = 40): List<WeatherParticle> {
        require(count in 0..MAX_WEATHER_PARTICLES) { "count must be 0..$MAX_WEATHER_PARTICLES, got $count" }
        val random = Random(seed)
        return List(count) {
            WeatherParticle(
                x = random.nextFloat(),
                y = random.nextFloat(),
                speed = 0.05f + random.nextFloat() * 0.12f,
                size = 0.5f + random.nextFloat(),
                phase = random.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }
}

/** Maximum alpha of any weather mark. */
private const val MAX_ALPHA = 0.35f

/**
 * The sea weather behind a screen: drawn only when [enabled] (the setting is on and motion
 * is allowed, see `weatherActive`) and the battery saver is off. Put it first in a Box, so the content draws
 * over it; the boards are opaque, so the weather shows only outside the grids.
 */
@Composable
fun SeaWeatherBackdrop(kind: WeatherKind, enabled: Boolean, modifier: Modifier = Modifier) {
    val batterySaver = rememberBatterySaver()
    if (enabled && !batterySaver && kind != WeatherKind.CALM) WeatherLayer(kind, modifier)
}

/**
 * Ambient weather layer. Put it in a Box behind the board, sized to the area around the grid: it never
 * draws over the cells. Under reduced motion it stands still; the game screens do not show it at all (see
 * [SeaWeatherBackdrop]).
 */
@Composable
fun WeatherLayer(kind: WeatherKind, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = LocalFleetColors.current
    val particles = remember(kind) { WeatherParticles.generate(kind) }
    val clock = remember { mutableLongStateOf(0L) }
    val animated = !reduced && kind != WeatherKind.CALM && kind != WeatherKind.MOON
    LaunchedEffect(kind, reduced) {
        if (!animated) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            clock.longValue = (now - start) / 1_000_000L
        }
    }
    Canvas(modifier.fillMaxSize()) {
        drawWeather(kind, particles, clock.longValue, colors.text, colors.accent, colors.miss)
    }
}

private fun DrawScope.drawWeather(
    kind: WeatherKind,
    particles: List<WeatherParticle>,
    clockMs: Long,
    text: Color,
    accent: Color,
    miss: Color,
) {
    val w = size.width
    val h = size.height
    val t = clockMs / 1000f
    when (kind) {
        WeatherKind.CALM -> Unit
        WeatherKind.RAIN -> drawRain(particles, t, text, w, h, streak = 0.04f)
        WeatherKind.STORM -> {
            drawRain(particles, t, text, w, h, streak = 0.06f)
            // A faint bolt every six seconds, in the sky above the sea; never a full-screen flash.
            val cycle = t % 6f
            if (cycle in 0f..0.25f) {
                val x = w * (0.2f + 0.6f * (particles.first().x))
                drawLine(accent, Offset(x, 0f), Offset(x - w * 0.03f, h * 0.12f), 2f, alpha = MAX_ALPHA * 0.5f)
            }
        }
        WeatherKind.SNOW -> for (p in particles) {
            val y = ((p.y + p.speed * t * 0.4f) % 1f) * h
            val x = (p.x + 0.02f * sin(t + p.phase)) % 1f * w
            drawCircle(text, p.size * 2.5f, Offset(x, y), alpha = MAX_ALPHA)
        }
        WeatherKind.FOG -> for (p in particles.take(12)) {
            val x = ((p.x + 0.03f * t * p.speed) % 1f) * w
            drawCircle(text, w * 0.25f * p.size, Offset(x, p.y * h), alpha = 0.08f)
        }
        WeatherKind.AURORA -> for (p in particles.take(4)) {
            val x = (p.x + 0.02f * sin(t * 0.5f + p.phase)) * w
            drawLine(accent, Offset(x, 0f), Offset(x + w * 0.1f, h * 0.35f), w * 0.05f * p.size, alpha = 0.12f)
        }
        WeatherKind.ALGAE -> for (p in particles) {
            val y = ((p.y + p.speed * t * 0.1f) % 1f) * h
            drawCircle(accent, p.size * 2f, Offset(p.x * w, y), alpha = 0.2f)
        }
        WeatherKind.STARS -> for (p in particles) {
            val twinkle = 0.5f + 0.5f * sin(t * 2f + p.phase)
            drawCircle(text, p.size * 1.5f, Offset(p.x * w, p.y * h * 0.5f), alpha = MAX_ALPHA * twinkle)
        }
        WeatherKind.DEEP -> for (p in particles) {
            val y = (1f - ((p.y + p.speed * t * 0.05f) % 1f)) * h
            drawCircle(miss, p.size * 2f, Offset(p.x * w, y), alpha = 0.2f, style = Stroke(1f))
        }
        WeatherKind.GULLS -> for (p in particles.take(3)) {
            val x = ((p.x + p.speed * t * 0.05f) % 1f) * w
            val y = p.y * h * 0.3f
            drawLine(text, Offset(x - 8f * p.size, y - 4f), Offset(x, y), 1.5f, alpha = 0.25f)
            drawLine(text, Offset(x, y), Offset(x + 8f * p.size, y - 4f), 1.5f, alpha = 0.25f)
        }
        WeatherKind.MOON -> drawCircle(text, minOf(w, h) * 0.06f, Offset(w * 0.85f, h * 0.1f), alpha = MAX_ALPHA)
    }
}

private fun DrawScope.drawRain(particles: List<WeatherParticle>, t: Float, colour: Color, w: Float, h: Float, streak: Float) {
    for (p in particles) {
        val y = ((p.y + p.speed * t * 1.5f) % 1f) * h
        val x = p.x * w
        drawLine(colour, Offset(x, y), Offset(x - streak * w, y + streak * h), 1f, alpha = 0.25f)
    }
}
