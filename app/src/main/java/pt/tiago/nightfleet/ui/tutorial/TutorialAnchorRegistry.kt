package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * The window bounds of every registered anchor. Screens register elements with [Modifier.tutorialAnchor]; a
 * canvas that draws several targets (the sea board, one square per cell) reports them with [put].
 */
class TutorialAnchorRegistry {
    private val bounds = mutableStateMapOf<String, Rect>()

    /** Records the window bounds of [anchorId]. Only the ids that [TutorialAnchor.isAllowed] accepts are kept. */
    fun put(anchorId: String, windowBounds: Rect) {
        require(TutorialAnchor.isAllowed(anchorId)) { "Unknown tutorial anchor: $anchorId" }
        if (bounds[anchorId] != windowBounds) bounds[anchorId] = windowBounds
    }

    fun remove(anchorId: String) {
        bounds.remove(anchorId)
    }

    /** The last reported window bounds of [anchorId], or null when it is not on screen. */
    fun boundsOf(anchorId: String): Rect? = bounds[anchorId]
}

/**
 * Provides the registry to a subtree. Without a provider, [Modifier.tutorialAnchor] does nothing, so the
 * screens cost nothing outside the tutorial.
 */
val LocalTutorialAnchors = staticCompositionLocalOf<TutorialAnchorRegistry?> { null }

/** Registers the bounds of this element under [anchorId] while it is composed. */
@Composable
fun Modifier.tutorialAnchor(anchorId: String): Modifier {
    val registry = LocalTutorialAnchors.current ?: return this
    DisposableEffect(registry, anchorId) {
        onDispose { registry.remove(anchorId) }
    }
    return this.onGloballyPositioned { registry.put(anchorId, it.boundsInWindow()) }
}
