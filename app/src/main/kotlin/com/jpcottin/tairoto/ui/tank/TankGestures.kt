package com.jpcottin.tairoto.ui.tank

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
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
}

private fun Offset.vec() = Vec2(x, y)

/** How long a finger must rest to drop a pellet. */
const val LONG_PRESS_MS = 450L

/**
 * Touch: a tap ripples the water, a long press drops a pellet, one finger
 * dragging sprinkles flakes, two fingers pan and pinch.
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
            var longPressed = false
            var last = start
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
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    if (sprinkling) {
                        handler.onSprinkleEnd()
                        sprinkling = false
                    }
                    multi = true
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (pan != Offset.Zero) handler.onPan(pan.vec())
                    if (zoom != 1f && centroid != Offset.Unspecified) handler.onZoom(centroid.vec(), zoom)
                    event.changes.forEach { it.consume() }
                } else if (!multi) {
                    val p = pressed.first().position
                    if ((p - start).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (moved) {
                        if (mouse) handler.onPan((p - last).vec())
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
