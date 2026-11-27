package com.jpcottin.tairoto.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jpcottin.tairoto.engine.NativeTank
import com.jpcottin.tairoto.world.SavedLagoon
import com.jpcottin.tairoto.world.Vec2
import java.io.File

/** The lagoon on a watch: the whole round screen is water. */
class WristActivity : ComponentActivity() {
    private lateinit var tank: NativeTank
    private lateinit var lagoon: WristLagoon
    private val file by lazy { File(filesDir, "wrist-lagoon.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tank = NativeTank()
        val saved = runCatching { if (file.isFile) SavedLagoon.decode(file.readText()) else null }.getOrNull()
        lagoon = WristLagoon(tank, saved)
        val dpi = resources.displayMetrics.xdpi
        setContent {
            val focus = remember { FocusRequester() }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF06232B))
                    .onRotaryScrollEvent { lagoon.crown(it.verticalScrollPixels / CROWN_PX_PER_STEP); true }
                    .focusRequester(focus)
                    .focusable(),
            ) {
                AndroidExternalSurface(Modifier.fillMaxSize()) {
                    onSurface { surface, width, height ->
                        tank.setSurface(surface)
                        lagoon.onScreen(width, height, dpi)
                        surface.onChanged { w, h -> lagoon.onScreen(w, h, dpi) }
                        surface.onDestroyed { tank.setSurface(null) }
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "The lagoon. Tap the water; drag to sprinkle food; hold for a pellet; turn the crown to zoom." }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { lagoon.tap(Vec2(it.x, it.y)) },
                                onLongPress = { lagoon.pellet(Vec2(it.x, it.y)) },
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { lagoon.sprinkle(Vec2(it.x, it.y), first = true) },
                                onDrag = { change, _ -> lagoon.sprinkle(Vec2(change.position.x, change.position.y), first = false) },
                                onDragEnd = { lagoon.endSprinkle() },
                                onDragCancel = { lagoon.endSprinkle() },
                            )
                        },
                )
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }

    override fun onResume() {
        super.onResume()
        lagoon.resume()
    }

    override fun onPause() {
        lagoon.pause()?.let { scene -> runCatching { file.writeText(SavedLagoon.encode(scene)) } }
        super.onPause()
    }

    override fun onDestroy() {
        tank.close()
        super.onDestroy()
    }

    private companion object {
        /** The crown's pixels of scroll that make one step of zoom. */
        const val CROWN_PX_PER_STEP = 40f
    }
}
