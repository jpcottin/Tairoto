package com.jpcottin.tairoto.wear

import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.SceneSync
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreaturePlace
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import com.jpcottin.tairoto.world.withPlaces
import kotlin.random.Random

/**
 * A lagoon on the wrist: a small one, a few fish, a crab and a coral head
 * the size of the watch's own patch of water. A tap on the glass startles
 * them, a finger dragged sprinkles flakes, a long press drops a pellet, and
 * the crown zooms. The engine is the phone's; this is all the watch adds.
 */
class WristLagoon(
    private val engine: TankEngine,
    /** The lagoon kept from last time, if any. */
    saved: TankScene? = null,
    private val random: Random = Random.Default,
) {
    private val sync = SceneSync(engine)
    var scene: TankScene? = saved
        private set
    var camera: Camera? = null
        private set
    private var viewport: Viewport? = null
    private var lastSprinkle: Vec2? = null
    private var carry = 0f

    /** The watch's screen: the lagoon is made for it the first time, and the camera looks at its middle. */
    fun onScreen(widthPx: Int, heightPx: Int, dpi: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val screen = ScreenMm.fromPixels(widthPx, heightPx, dpi, dpi, bezelMm = 0f)
        val vp = Viewport(widthPx.toFloat(), heightPx.toFloat(), ScreenMm.pxPerMm(dpi))
        viewport = vp
        val sc = scene ?: starter(TankSize.forScreen(screen, areaFactor = AREA_FACTOR), random).also { scene = it }
        engine.setQuality(QualityTier.LOW)
        sync.apply(sc)
        // Life size, a fish would fill a watch: it starts zoomed out, and the crown brings them close.
        camera = (camera ?: Camera.initial(vp, sc.size).zoomedAbout(vp.center, START_ZOOM, vp, sc.size)).clamped(vp, sc.size)
        engine.setCamera(camera!!, vp)
    }

    fun tap(px: Vec2) {
        val at = toWater(px) ?: return
        engine.tap(at)
    }

    fun pellet(px: Vec2) {
        val at = toWater(px) ?: return
        engine.dropFood(at, FoodKind.PELLET, 1)
    }

    /** A finger dragging: a pinch where it lands, then a flake every few millimetres of its way. */
    fun sprinkle(px: Vec2, first: Boolean) {
        val at = toWater(px) ?: return
        val previous = lastSprinkle
        if (first || previous == null) {
            engine.dropFood(at, FoodKind.FLAKE, FIRST_PINCH)
            carry = 0f
        } else {
            carry += (at - previous).length
            val n = (carry / SPACING_MM).toInt()
            if (n > 0) {
                engine.dropFood(at, FoodKind.FLAKE, n.coerceAtMost(4))
                carry -= n * SPACING_MM
            }
        }
        lastSprinkle = at
    }

    fun endSprinkle() {
        lastSprinkle = null
    }

    /** The crown: [steps] clicks of it, positive away from the wearer, zoom in. */
    fun crown(steps: Float) {
        val vp = viewport ?: return
        val size = scene?.size ?: return
        val factor = Math.pow(ZOOM_PER_STEP.toDouble(), steps.toDouble()).toFloat()
        camera = camera?.zoomedAbout(vp.center, factor, vp, size)
        camera?.let { engine.setCamera(it, vp) }
    }

    /** Off the wrist, or the screen off: the lagoon rests, and where everyone was is kept. */
    fun pause(): TankScene? {
        engine.setPaused(true)
        val sc = scene ?: return null
        val places = runCatching { engine.snapshot() }.getOrDefault(emptyList()).associate { it.id to CreaturePlace(Vec2(it.x, it.y), it.z) }
        return sc.withPlaces(places).also { scene = it }
    }

    fun resume() = engine.setPaused(false)

    private fun toWater(px: Vec2): Vec2? {
        val vp = viewport ?: return null
        return camera?.screenToWorld(px, 0f, vp)
    }

    companion object {
        /** A small patch of lagoon round the watch: three times its screen, to pan a little over by zooming. */
        const val AREA_FACTOR = 3f
        const val FIRST_PINCH = 3
        const val SPACING_MM = 3f
        const val ZOOM_PER_STEP = 1.08f
        const val START_ZOOM = 0.5f

        /** A lagoon for a watch: a school of ʻatoti, a few manini, a hermit crab, round a coral head. */
        fun starter(size: TankSize, random: Random): TankScene {
            val ids = EntityIdAllocator(0)
            val middle = Vec2(size.widthMm / 2f, size.heightMm / 2f)
            val coral = DecorCatalog.make(DecorCatalog.require("coral_head"), middle + Vec2(size.widthMm * 0.12f, size.heightMm * 0.1f), ids, random)
            val creatures = Population.release(SpeciesCatalog.GreenChromis, 7, size, ids, random, around = middle) +
                Population.release(SpeciesCatalog.ConvictSurgeonfish, 3, size, ids, random, around = middle) +
                Population.release(SpeciesCatalog.HermitCrab, 1, size, ids, random, around = middle)
            return TankScene(size, creatures, listOf(coral))
        }
    }
}
