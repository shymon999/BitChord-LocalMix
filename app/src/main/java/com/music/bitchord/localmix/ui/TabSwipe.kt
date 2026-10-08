package com.music.bitchord.localmix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Drags starting this close to a side edge are left to the system back gesture. */
private val EDGE_GUARD = 24.dp

/** A release this fast counts as a swipe however short it was. */
private val FLING_VELOCITY = 600.dp

/** How much of the finger's travel the page follows while the swipe is undecided. */
private const val FOLLOW_FACTOR = 0.25f

/** The share of the page's width a slow drag must cover to change tab. */
private const val DISTANCE_FRACTION = 0.22f

/**
 * Swiping sideways over an empty part of a tab page moves to the neighbouring tab:
 * right to left goes to the next tab in the bar, left to right to the previous one.
 * [onSwipe] gets +1 or -1 in bar order (mirrored for right-to-left layouts).
 *
 * The gesture is read after the page's own children (the Main pass), so a
 * carousel, a slider or anything else that has already taken the drag keeps
 * it, and a mostly vertical drag is left to the list. Only once a drag is
 * clearly sideways and still untaken does it claim it, consuming the rest of
 * it ahead of the children so nothing underneath scrolls or clicks as well.
 * Drags that start in the system's side gesture insets are never looked at,
 * so the edge "back" swipe stays the system's.
 */
fun Modifier.tabSwipe(enabled: Boolean, onSwipe: (direction: Int) -> Unit): Modifier = composed {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val screenDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val gestureInsets = WindowInsets.systemGestures
    val follow = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    if (!enabled) {
        return@composed this
    }

    this
        .pointerInput(layoutDirection) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val edgeGuard = EDGE_GUARD.toPx()
                val left = maxOf(gestureInsets.getLeft(screenDensity, layoutDirection).toFloat(), edgeGuard)
                val right = maxOf(gestureInsets.getRight(screenDensity, layoutDirection).toFloat(), edgeGuard)
                if (down.position.x < left || down.position.x > size.width - right) return@awaitEachGesture

                val slop = viewConfiguration.touchSlop
                val pointerId = down.id
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                var total = Offset.Zero

                // Watching: the children have had their go at every event first.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.size > 1) return@awaitEachGesture
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: return@awaitEachGesture
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    total += change.positionChangeIgnoreConsumed()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (abs(total.y) > slop) return@awaitEachGesture
                    if (abs(total.x) > slop && abs(total.x) > abs(total.y) * 2) {
                        change.consume()
                        break
                    }
                }

                // Claimed: take every further event before the children see it.
                var dx = total.x
                var released = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    dx += change.positionChangeIgnoreConsumed().x
                    velocity.addPosition(change.uptimeMillis, change.position)
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) {
                        released = true
                        break
                    }
                    scope.launch { follow.snapTo(dx * FOLLOW_FACTOR) }
                }

                val vx = velocity.calculateVelocity().x
                val fling = FLING_VELOCITY.toPx()
                val swiped = released && (
                    abs(dx) > size.width * DISTANCE_FRACTION ||
                        (abs(vx) > fling && vx * dx > 0)
                    )
                if (swiped) {
                    val towardsNext = dx < 0
                    val forward = if (layoutDirection == LayoutDirection.Rtl) !towardsNext else towardsNext
                    scope.launch { follow.snapTo(0f) }
                    currentOnSwipe(if (forward) 1 else -1)
                } else {
                    scope.launch { follow.animateTo(0f, spring()) }
                }
            }
        }
        .graphicsLayer { translationX = follow.value }
}
