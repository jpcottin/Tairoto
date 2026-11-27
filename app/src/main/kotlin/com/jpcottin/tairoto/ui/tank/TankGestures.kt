package com.jpcottin.tairoto.ui.tank

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import com.jpcottin.tairoto.world.Vec2
import kotlin.math.exp

/** What a hand (or a mouse) does on the glass. */
interface TankGestureHandler {
    fun onTap(at: Vec2)
    fun onPan(delta: Vec2)
    fun onZoom(focus: Vec2, factor: Float)
    /** A finger dragging over the water; [first] on the first move of the drag. */
    fun onSprinkle(at: Vec2, first: Boolean) = Unit
    fun onSprinkleEnd() = Unit
    fun onLongPress(at: Vec2) = Unit
    /** A drag begins at [at]: true to take it (moving a decoration) instead of sprinkling. */
    fun onDragStart(at: Vec2): Boolean = false
    fun onDrag(at: Vec2) = Unit
    /** Two fingers twisting, by this many radians. */
    fun onRotate(radians: Float) = Unit
    /** Every finger has left the glass. */
    fun onGestureEnd() = Unit
    /** One finger, put down at [start], began to move toward [toward] (it came on over an edge?). */
    fun onStrokeStart(start: Vec2, toward: Vec2) = Unit
    /** One finger, moving from [from] a moment before, was lifted at [end] (it went off over an edge?). */
    fun onStrokeEnd(from: Vec2, end: Vec2) = Unit
}

private fun Offset.vec() = Vec2(x, y)

/** How long a finger must rest to drop a pellet. */
const val LONG_PRESS_MS = 450L

/** Where a finger was this long before it was lifted gives the way it was going. */
private const val STROKE_TAIL_MS = 40L

/**
 * Touch: a tap ripples the water, a long press drops a pellet, one finger
 * dragging sprinkles flakes (or, when the handler takes the drag, moves a
 * decoration), two fingers pan, pinch and twist.
 * Mouse and trackpad: drag pans, the wheel zooms about the pointer.
 */
fun Modifier.tankGestures(handler: TankGestureHandler): Modifier = this
    .pointerInput(handler) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            val startTime = down.uptimeMillis
            val mouse = down.type == PointerType.Mouse
            var multi = false
            var moved = false
            var sprinkling = false
            var dragging = false
            var longPressed = false
            var last = start
            // Where the finger was lately, to tell the way it was going when lifted.
            val trail = ArrayDeque<Pair<Long, Offset>>()
            trail.addLast(startTime to start)
            var end = start
            while (true) {
                val event = if (!moved && !multi && !longPressed && !mouse) {
                    val left = LONG_PRESS_MS - (currentEvent.changes.firstOrNull()?.uptimeMillis?.minus(startTime) ?: 0L)
                    withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    // Held still long enough: a pellet.
                    longPressed = true
                    handler.onLongPress(start.vec())
                    continue
                }
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    end = event.changes.firstOrNull()?.position ?: last
                    break
                }
                if (pressed.size >= 2) {
                    if (sprinkling) {
                        handler.onSprinkleEnd()
                        sprinkling = false
                    }
                    multi = true
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val rotation = event.calculateRotation()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (pan != Offset.Zero) handler.onPan(pan.vec())
                    if (zoom != 1f && centroid != Offset.Unspecified) handler.onZoom(centroid.vec(), zoom)
                    if (rotation != 0f) handler.onRotate(rotation * (Math.PI / 180.0).toFloat())
                    event.changes.forEach { it.consume() }
                } else if (!multi) {
                    val p = pressed.first().position
                    if (!moved && (p - start).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        dragging = !longPressed && handler.onDragStart(start.vec())
                        if (!mouse) handler.onStrokeStart(start.vec(), p.vec())
                    }
                    val t = pressed.first().uptimeMillis
                    trail.addLast(t to p)
                    while (trail.size > 2 && t - trail[1].first >= STROKE_TAIL_MS) trail.removeFirst()
                    if (moved) {
                        if (dragging) handler.onDrag(p.vec())
                        else if (mouse) handler.onPan((p - last).vec())
                        else if (!longPressed) {
                            handler.onSprinkle(p.vec(), first = !sprinkling)
                            sprinkling = true
                        }
                        pressed.first().consume()
                    }
                    last = p
                }
            }
            if (sprinkling) handler.onSprinkleEnd()
            if (moved && !multi && !mouse) handler.onStrokeEnd(trail.first().second.vec(), end.vec())
            handler.onGestureEnd()
            val quick = (currentEvent.changes.firstOrNull()?.uptimeMillis ?: startTime) - startTime < LONG_PRESS_MS
            if (!multi && !moved && !longPressed && quick) handler.onTap(start.vec())
        }
    }
    .pointerInput(handler) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Scroll) {
                    val change = event.changes.first()
                    val dy = change.scrollDelta.y
                    if (dy != 0f) handler.onZoom(change.position.vec(), exp(-dy * 0.12f))
                    change.consume()
                }
            }
        }
    }
