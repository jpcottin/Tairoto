package com.jpcottin.tairoto.ambient

import kotlinx.coroutines.flow.first
import com.jpcottin.tairoto.world.Daylight
import android.view.Surface
import com.jpcottin.tairoto.data.LagoonStore
import com.jpcottin.tairoto.engine.SceneSync
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.world.AmbientView
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The lagoon where nobody holds it: a live wallpaper, a screensaver. The
 * lagoon saved by the app, read and never written (the app owns it), or a
 * new one if there is none; drawn at the Low tier and silent, to be kind to
 * the battery; paused whenever it is not seen.
 *
 * The camera follows the home screens ([offset]) or, with [drift], roams on
 * its own.
 */
class LagoonPlayer(
    private val engine: TankEngine,
    private val store: LagoonStore,
    private val scope: CoroutineScope,
    private val drift: Boolean,
    private val clockS: () -> Double,
    /** The light the app's settings ask for now (the local time of day, or always day or night). */
    private val daylight: suspend () -> Daylight = { Daylight.NOON },
) {
    private val sync = SceneSync(engine)
    private var scene: TankScene? = null
    private var viewport: Viewport? = null
    private var offset = Vec2(0.5f, 0.5f)
    private var camera: Camera? = null
    private var loading: Job? = null
    private var drifting: Job? = null
    private var visible = false

    init {
        engine.setQuality(QualityTier.LOW)
        engine.setMaxFps(AMBIENT_FPS)
        engine.setSound(0f)
        engine.setPaused(true)
    }

    /** The surface to draw into (null in tests, which have none), its size in pixels and the display's density. */
    fun surface(surface: Surface?, widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        engine.setSurface(surface)
        viewport = Viewport(widthPx.toFloat(), heightPx.toFloat(), ScreenMm.pxPerMm((xdpi + ydpi) / 2f))
        if (scene == null && loading == null) {
            val screen = ScreenMm.fromPixels(widthPx, heightPx, xdpi, ydpi)
            loading = scope.launch {
                val s = runCatching { store.load() }.getOrNull() ?: fresh(TankSize.forScreen(screen))
                scene = s
                sync.apply(s)
                look()
            }
        } else {
            look()
        }
    }

    fun surfaceGone() = engine.setSurface(null)

    /** Seen or not: it only swims, and only draws, when seen. */
    fun visible(seen: Boolean) {
        visible = seen
        engine.setPaused(!seen)
        // Back on screen: the app may have changed the lagoon meanwhile (creatures released, coral moved).
        if (seen) scope.launch { runCatching { daylight() }.getOrNull()?.let { engine.setDaylight(it) } }
        if (seen && scene != null) {
            scope.launch {
                val now = runCatching { store.load() }.getOrNull() ?: return@launch
                if (now != scene) {
                    scene = now
                    sync.apply(now)
                    look()
                }
            }
        }
        drifting?.cancel()
        drifting = null
        if (seen && drift) {
            drifting = scope.launch {
                var steps = 0
                while (isActive) {
                    // A screensaver runs for hours: the light follows the clock.
                    if (++steps % DAYLIGHT_STEPS == 0) runCatching { daylight() }.getOrNull()?.let { engine.setDaylight(it) }
                    look()
                    delay(DRIFT_STEP_MS)
                }
            }
        }
    }

    /** The home screens were swiped to [x] (and [y]), 0..1. */
    fun offset(x: Float, y: Float) {
        offset = Vec2(x, y)
        if (!drift) look()
    }

    /** A finger on the glass: a ripple, and the fish nearby dart off. */
    fun tap(atPx: Vec2) {
        val cam = camera ?: return
        val vp = viewport ?: return
        engine.tap(cam.screenToWorld(atPx, 0f, vp))
    }

    fun close() {
        loading?.cancel()
        drifting?.cancel()
        engine.setSurface(null)
        engine.close()
    }

    private fun look() {
        val vp = viewport ?: return
        val size = scene?.size ?: return
        val cam = if (drift) AmbientView.drifting(clockS(), vp, size) else AmbientView.forOffset(offset.x, offset.y, vp, size)
        camera = cam
        engine.setCamera(cam, vp)
    }

    private fun fresh(size: TankSize): TankScene = Population.defaultScene(size, EntityIdAllocator(0), Random.Default)

    companion object {
        /** Drift steps between two looks at the clock for the light. */
        const val DAYLIGHT_STEPS = 600
        /** The drift moves a mm or two a second: thirty steps a second keep each under a pixel. */
        const val DRIFT_STEP_MS = 33L
        /** Fish behind the icons need no more: half the frames, half the battery. */
        const val AMBIENT_FPS = 30
    }
}

/** The light the app's settings ask for now, for the wallpaper and the screensaver. */
internal suspend fun settingsDaylight(context: android.content.Context): com.jpcottin.tairoto.world.Daylight {
    val time = com.jpcottin.tairoto.data.DataStoreSettings(context).settings.first().timeOfDay
    val now = java.time.LocalTime.now()
    return time.daylight(now.hour + now.minute / 60f)
}
