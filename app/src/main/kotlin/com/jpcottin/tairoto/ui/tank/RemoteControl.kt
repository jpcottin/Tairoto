package com.jpcottin.tairoto.ui.tank

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.jpcottin.tairoto.world.Vec2

/** What the keys of a remote, a keyboard or a controller do to the lagoon. */
interface RemoteActions {
    fun tap(at: Vec2)
    fun pellet(at: Vec2)
    fun sprinkle(at: Vec2)
    fun pan(delta: Vec2)
    fun zoom(at: Vec2, factor: Float)
}

/** The cursor's state, shared by the keys and the ring that shows it. */
@Stable
class RemoteState {
    internal val cursor = RemoteCursor()
    var at by mutableStateOf<Vec2?>(null)
        internal set
    var focused by mutableStateOf(false)
        internal set
    internal var size = IntSize.Zero
    internal var held = false
    internal var longDone = false
    val focus = FocusRequester()
}

/**
 * The water takes keys: arrows move a cursor (and pan the lagoon at the
 * edges), OK taps the glass there and, held, drops a pellet; F, X or Play
 * sprinkles flakes; + and - (or the channel keys) zoom. Up from the top edge
 * goes to the buttons above, as anywhere else on a TV.
 */
fun Modifier.remoteControl(state: RemoteState, actions: RemoteActions, focusManager: FocusManager): Modifier = this
    .onSizeChanged { state.size = it }
    .focusRequester(state.focus)
    .onFocusChanged {
        state.focused = it.isFocused
        if (!it.isFocused) {
            state.cursor.hide()
            state.at = null
        }
    }
    .onKeyEvent { e ->
        val w = state.size.width.toFloat()
        val h = state.size.height.toFloat()
        if (w <= 0f || h <= 0f) return@onKeyEvent false
        val repeats = e.nativeKeyEvent.repeatCount
        when (e.type) {
            KeyEventType.KeyDown -> when (e.key) {
                Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                    if (e.key == Key.DirectionUp && state.at != null && state.cursor.atTop(h, w)) {
                        state.cursor.hide()
                        state.at = null
                        // The water fills the screen, so nothing lies "above" it: the buttons come next.
                        focusManager.moveFocus(FocusDirection.Next)
                    } else {
                        val dx = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
                        val dy = when (e.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; else -> 0 }
                        val pan = state.cursor.move(dx, dy, repeats, w, h)
                        state.at = state.cursor.at
                        if (pan.x != 0f || pan.y != 0f) actions.pan(pan)
                    }
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA, Key.Spacebar -> {
                    val at = state.cursor.show(w, h).also { state.at = it }
                    if (repeats == 0) {
                        state.held = true
                        state.longDone = false
                    } else if (!state.longDone) {
                        // Held: a pellet, and no tap when it lets go.
                        state.longDone = true
                        actions.pellet(at)
                    }
                    true
                }
                Key.F, Key.ButtonX, Key.MediaPlayPause, Key.MediaPlay -> {
                    actions.sprinkle(state.cursor.show(w, h).also { state.at = it })
                    true
                }
                Key.Plus, Key.Equals, Key.NumPadAdd, Key.ChannelUp, Key.ZoomIn, Key.ButtonR1 -> {
                    actions.zoom(state.cursor.show(w, h).also { state.at = it }, ZOOM_STEP)
                    true
                }
                Key.Minus, Key.NumPadSubtract, Key.ChannelDown, Key.ZoomOut, Key.ButtonL1 -> {
                    actions.zoom(state.cursor.show(w, h).also { state.at = it }, 1f / ZOOM_STEP)
                    true
                }
                else -> false
            }
            KeyEventType.KeyUp -> when (e.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA, Key.Spacebar -> {
                    if (state.held && !state.longDone) state.at?.let { actions.tap(it) }
                    state.held = false
                    true
                }
                else -> false
            }
            else -> false
        }
    }
    .focusable()

private const val ZOOM_STEP = 1.25f

/** Where the cursor is: a ring on the water, while the water has the keys. */
@Composable
fun RemoteCursorRing(state: RemoteState) {
    val at = state.at ?: return
    if (!state.focused) return
    Canvas(Modifier.fillMaxSize().testTag(TankTags.CURSOR)) {
        val r = size.minDimension * 0.035f
        drawCircle(Color(0x55000000), r + 3f, Offset(at.x, at.y), style = Stroke(width = 9f))
        drawCircle(Color(0xEEF2FBF8), r, Offset(at.x, at.y), style = Stroke(width = 5f))
        drawCircle(Color(0xEEF2FBF8), 4f, Offset(at.x, at.y))
    }
}
