package pt.tiago.nightfleet.ui.board

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * True when the board must not animate: effects become a short fade, the scenery stands still and the
 * screen does not shake. The default is false; [ReducedMotionProvider] sets it from the system.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Reads the system "remove animations" setting (animator duration scale 0). */
fun systemReducedMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: RuntimeException) {
    false
}

/**
 * Provides [LocalReducedMotion]: [override] when given, otherwise the system setting. Wrap the screens
 * that contain boards (the battle screen, the gallery).
 */
@Composable
fun ReducedMotionProvider(override: Boolean? = null, content: @Composable () -> Unit) {
    val system = systemReducedMotion(LocalContext.current)
    CompositionLocalProvider(LocalReducedMotion provides (override ?: system), content = content)
}
