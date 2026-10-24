package pt.tiago.nightfleet.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.ui.board.LocalReducedMotion
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import kotlin.math.roundToInt

private val SPOTLIGHT_PADDING = 8.dp
private val SPOTLIGHT_RADIUS = 12.dp
private val SAFE_MARGIN = 16.dp
private val BUBBLE_GAP = 16.dp
private val BUBBLE_MAX_WIDTH = 320.dp
private val HAND_RADIUS = 12.dp
private const val SCRIM_ALPHA = 0.72f
private const val HAND_LOOP_MILLIS = 1600

/**
 * The coach mark. Draws nothing when [state] has no step.
 *
 * - The screen dims at 72 % except a rounded spotlight around the step's anchor. On an action step the taps on
 *   the spotlight go through to the screen; everywhere else the taps are taken by the overlay.
 * - The bubble sits above or below the anchor, inside the safe area, and its text is announced (polite live
 *   region) and receives the focus when the step starts.
 * - A hand taps or drags across the anchor; with reduced motion it stays still in the middle.
 * - Back opens a confirmation inside the overlay; confirming it calls [onSkip].
 *
 * [onAdvance] is called for a tap on a tap-anywhere or timed step, and when a timed step ends. Wire the screen's
 * events to `TutorialController.onAction` (the action steps) and call `onAdvance = controller::onTap`.
 */
@Composable
fun TutorialOverlay(
    state: TutorialState,
    registry: TutorialAnchorRegistry,
    onAdvance: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = state.step ?: return
    val stepKey = "${state.script?.id}:${state.stepIndex}"
    ActiveStep(
        step = step,
        stepKey = stepKey,
        registry = registry,
        onAdvance = onAdvance,
        onSkip = onSkip,
        modifier = modifier,
    )
}

@Composable
private fun ActiveStep(
    step: TutorialStep,
    stepKey: String,
    registry: TutorialAnchorRegistry,
    onAdvance: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val reduced = LocalReducedMotion.current

    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
    var confirming by remember(stepKey) { mutableStateOf(false) }
    val focusRequester = remember(stepKey) { FocusRequester() }
    val currentAdvance by rememberUpdatedState(onAdvance)

    BackHandler { confirming = !confirming }

    // Timed steps move on by themselves; the timer is cancelled when the step changes.
    LaunchedEffect(stepKey) {
        val rule = step.advance
        if (rule is AdvanceRule.Auto) {
            delay(rule.millis)
            currentAdvance()
        }
    }

    val bubbleReady = bubbleSize != IntSize.Zero
    // The bubble takes the focus, so TalkBack reads it, once it has been placed.
    LaunchedEffect(stepKey, bubbleReady) {
        if (bubbleReady) {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // The focus node is not attached yet; the next layout pass retries.
            }
        }
    }

    val anchorWindow = registry.boundsOf(step.anchor.id)
    val anchor: Rect? = anchorWindow?.translate(-overlayOrigin.x, -overlayOrigin.y)
    val padding = with(density) { SPOTLIGHT_PADDING.toPx() }
    val hole: Rect? = anchor?.let { TutorialLayout.spotlight(it, padding) }
    val margin = with(density) { SAFE_MARGIN.toPx() }
    val gap = with(density) { BUBBLE_GAP.toPx() }
    val insets = WindowInsets.safeDrawing
    val safe = Rect(
        left = insets.getLeft(density, layoutDirection) + margin,
        top = insets.getTop(density) + margin,
        right = overlaySize.width - insets.getRight(density, layoutDirection) - margin,
        bottom = overlaySize.height - insets.getBottom(density) - margin,
    )
    val placement = TutorialLayout.placeBubble(
        safeArea = safe,
        anchor = anchor,
        bubble = Size(bubbleSize.width.toFloat(), bubbleSize.height.toFloat()),
        gapPx = gap,
    )
    val tapAdvances = step.advance !is AdvanceRule.OnAction

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.positionInWindow()
                overlaySize = it.size
            },
    ) {
        TutorialScrim(
            hole = hole,
            tapAdvances = tapAdvances,
            enabled = !confirming,
            stepKey = stepKey,
            onTap = currentAdvance,
        )
        if (anchor != null && step.gesture != GestureKind.NONE) {
            HandPointer(anchor = anchor, gesture = step.gesture, reduced = reduced)
        }
        Column(
            Modifier
                .offset { IntOffset(placement.topLeft.x.roundToInt(), placement.topLeft.y.roundToInt()) }
                .alpha(if (bubbleReady) 1f else 0f)
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .onSizeChanged { bubbleSize = it }
                .background(colors.surface, RoundedCornerShape(shapes.card))
                .border(1.dp, colors.line, RoundedCornerShape(shapes.card))
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = namedString(step.textKey),
                style = type.bodyText,
                color = colors.text,
                modifier = Modifier.focusRequester(focusRequester).focusable(),
            )
            if (tapAdvances) {
                Text(
                    text = stringResource(R.string.tutorial_tap_to_continue),
                    style = type.caption,
                    color = colors.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(
                        text = stringResource(R.string.tutorial_skip),
                        style = type.button,
                        color = colors.accent,
                    )
                }
            }
        }
        if (confirming) {
            SkipConfirmation(onKeepGoing = { confirming = false }, onSkip = onSkip)
        }
    }
}

/** The dimmed screen with the spotlight cut out. Taps are taken here unless they fall on a pass-through spotlight. */
@Composable
private fun TutorialScrim(
    hole: Rect?,
    tapAdvances: Boolean,
    enabled: Boolean,
    stepKey: String,
    onTap: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val density = LocalDensity.current
    val radius = with(density) { SPOTLIGHT_RADIUS.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(stepKey, hole, tapAdvances, enabled) {
                awaitEachGesture {
                    if (!enabled) return@awaitEachGesture
                    val down = awaitFirstDown()
                    val onSpotlight = hole?.contains(down.position) == true
                    // Action steps: a tap on the spotlight is left for the screen underneath.
                    if (onSpotlight && !tapAdvances) return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation()
                    if (up != null) {
                        up.consume()
                        if (tapAdvances) onTap()
                    }
                }
            },
    ) {
        drawRect(color = colors.bg.copy(alpha = SCRIM_ALPHA))
        if (hole != null) {
            drawRoundRect(
                color = Color.Transparent,
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(radius),
                blendMode = BlendMode.Clear,
            )
            drawRoundRect(
                color = colors.accent,
                topLeft = hole.topLeft,
                size = hole.size,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = strokeWidth),
            )
        }
    }
}

/** A hand that taps or drags across the anchor, looping. Static when reduced motion is on. */
@Composable
private fun HandPointer(anchor: Rect, gesture: GestureKind, reduced: Boolean) {
    if (reduced) {
        HandCanvas(anchor = anchor, gesture = gesture, progress = 0.5f, ripple = 0f)
        return
    }
    val transition = rememberInfiniteTransition(label = "tutorial-hand")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = HAND_LOOP_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tutorial-hand-progress",
    )
    HandCanvas(anchor = anchor, gesture = gesture, progress = progress, ripple = (progress * 2f) % 1f)
}

@Composable
private fun HandCanvas(anchor: Rect, gesture: GestureKind, progress: Float, ripple: Float) {
    val colors = LocalFleetColors.current
    val density = LocalDensity.current
    val radius = with(density) { HAND_RADIUS.toPx() }
    val strokeWidth = with(density) { 2.dp.toPx() }
    val rippleGrowth = with(density) { 18.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val position = TutorialLayout.handPosition(anchor, gesture, progress) ?: return@Canvas
        drawCircle(
            color = colors.accent.copy(alpha = 0.4f * (1f - ripple)),
            radius = radius + rippleGrowth * ripple,
            center = position,
        )
        drawCircle(color = colors.accent, radius = radius, center = position)
        drawCircle(color = colors.bg, radius = radius, center = position, style = Stroke(width = strokeWidth))
    }
}

/** The "skip?" question shown when back is pressed. */
@Composable
private fun SkipConfirmation(onKeepGoing: () -> Unit, onSkip: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .padding(horizontal = 16.dp)
                .background(colors.surface, RoundedCornerShape(shapes.card))
                .border(1.dp, colors.line, RoundedCornerShape(shapes.card))
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                text = stringResource(R.string.tutorial_skip_title),
                style = type.cardTitle,
                color = colors.text,
                modifier = Modifier.focusable(),
            )
            Text(
                text = stringResource(R.string.tutorial_skip_body),
                style = type.bodyText,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onKeepGoing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.tutorial_skip_keep), style = type.button, color = colors.accent)
                }
                Button(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.tutorial_skip_confirm), style = type.button)
                }
            }
        }
    }
}
