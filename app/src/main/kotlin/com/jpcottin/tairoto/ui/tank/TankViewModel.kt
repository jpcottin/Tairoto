package com.jpcottin.tairoto.ui.tank

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.AppSettings
import com.jpcottin.tairoto.data.InMemoryLagoonStore
import com.jpcottin.tairoto.data.LagoonStore
import com.jpcottin.tairoto.data.SettingsStore
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.SceneSync
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.Camera
import com.jpcottin.tairoto.world.CreaturePlace
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.DecorLayout
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.DepthProjection
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.LoopbackLink
import com.jpcottin.tairoto.world.PerfSample
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.QualityGovernor
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSession
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.UndoState
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import com.jpcottin.tairoto.world.withPlaces
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.random.Random

/** What the device can tell about its own state; real on a phone, fixed in tests. */
interface DeviceSignals {
    /** PowerManager's thermal headroom forecast (1.0 = about to throttle), or null. */
    fun thermalHeadroom(): Float?
    fun refreshRateHz(): Float
    fun nowMs(): Long

    /**
     * True on an emulator: its thermal HAL is simulated (a fixed headroom,
     * unrelated to how hot the host really runs), so it must not steer quality.
     */
    val thermalIsSimulated: Boolean get() = false
}

data class TankUiState(
    val ready: Boolean = false,
    val counts: Map<String, Int> = emptyMap(),
    val total: Int = 0,
    val roomLeft: Int = TankScene.MAX_CREATURES,
    val tankSize: TankSize? = null,
    val zoom: Float = 1f,
    val showStats: Boolean = false,
    val stats: EngineStats = EngineStats(),
    val tier: QualityTier = QualityTier.HIGH,
    val qualityMode: QualityMode = QualityMode.Auto,
    val hintVisible: Boolean = true,
    val vulkanUnsupported: Boolean = false,
    val gpu: String = "",
    /** Thermal headroom as last read (1.0 = about to throttle), or null when the device does not say. */
    val thermalHeadroom: Float? = null,
    /** The headroom above is shown but not used (an emulator). */
    val thermalIgnored: Boolean = false,
    /** Decorations on the floor, by kind, and how many more fit. */
    val decorCounts: Map<String, Int> = emptyMap(),
    val decorRoomLeft: Int = TankScene.MAX_DECOR,
    /** Arranging the lagoon: moving decorations, netting creatures. */
    val edit: EditUi = EditUi(),
)

/** What the hand does in edit mode. */
enum class EditTool { MOVE, NET }

/** Where on screen the selected decoration is, to draw a ring round it. */
data class SelectionUi(val id: EntityId, val centerPx: Vec2, val radiusPx: Float, val name: String)

/** The net's sweep, for the animation: where, how wide, whether it caught something. [seq] counts sweeps. */
data class NetSweep(val seq: Int, val centerPx: Vec2, val radiusPx: Float, val caught: String?)

data class EditUi(
    val active: Boolean = false,
    val tool: EditTool = EditTool.MOVE,
    val selection: SelectionUi? = null,
    val undo: UndoState = UndoState(),
    val sweep: NetSweep? = null,
)

/**
 * The tank on this device: the session (one way in for every change), the
 * native engine that draws it, the camera, and the quality governor. Lives as
 * long as the activity, so the tank carries on across rotation and settings.
 */
class TankViewModel(
    private val engine: TankEngine,
    private val settingsStore: SettingsStore,
    private val options: LaunchOptions,
    private val signals: DeviceSignals,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val lagoonStore: LagoonStore = InMemoryLagoonStore(),
) : ViewModel() {

    private val state = MutableStateFlow(TankUiState(hintVisible = options.hints))
    val ui: StateFlow<TankUiState> = state.asStateFlow()

    private var session: TankSession? = null
    private val sync = SceneSync(engine)
    private var viewport: Viewport? = null
    var camera: Camera? = null
        private set
    private var governor = QualityGovernor(QualityTier.HIGH)
    private var settings = AppSettings()
    private var statsJob: Job? = null
    private var lastTap: Pair<Long, Vec2>? = null
    private var firstRenderMs: Long? = null
    private var lastSprinkle: Vec2? = null
    private var sprinkleCarry = 0f

    /** The saved lagoon, once read (null inside if there was none); the tank waits for it. */
    private var restored: Result<TankScene?>? = if (options.persist) null else Result.success(null)
    private var pendingSurface: IntArray? = null
    private var pendingDpi: FloatArray? = null

    // Edit mode
    private var selected: EntityId? = null
    private var dragBefore: DecorSpec? = null
    private var dragOffset = Vec2.Zero
    private var lastPanPx: Vec2? = null
    private var sweeps = 0

    init {
        if (options.deterministic) engine.setDeterministic(true, options.seed ?: 7L)
        if (options.persist) {
            viewModelScope.launch {
                restored = Result.success(runCatching { lagoonStore.load() }.getOrNull())
                val size = pendingSurface ?: return@launch
                val dpi = pendingDpi ?: return@launch
                pendingSurface = null
                onSurfaceSize(size[0], size[1], dpi[0], dpi[1])
            }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                settings = s.copy(showStats = options.stats ?: s.showStats)
                state.update { it.copy(showStats = settings.showStats, qualityMode = s.quality) }
                applyQuality()
            }
        }
        if (options.hints) {
            viewModelScope.launch {
                delay(HINT_MS)
                state.update { it.copy(hintVisible = false) }
            }
        }
    }

    // ------------------------------------------------------------ surface

    /** The tank's surface is ready (or changed size). Creates the tank the first time. */
    fun onSurface(surface: Surface, widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float) {
        engine.setSurface(surface)
        onSurfaceSize(widthPx, heightPx, xdpi, ydpi)
        startStats()
    }

    /** The biggest window the lagoon has been shown in, in mm². */
    private var largestScreenMm2 = 0f

    fun onSurfaceSize(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (session == null && restored == null) {
            // The saved lagoon is still being read: make the tank when it is in.
            pendingSurface = intArrayOf(widthPx, heightPx)
            pendingDpi = floatArrayOf(xdpi, ydpi)
            return
        }
        val vp = Viewport(widthPx.toFloat(), heightPx.toFloat(), ScreenMm.pxPerMm((xdpi + ydpi) / 2f))
        viewport = vp
        val screen = ScreenMm.fromPixels(widthPx, heightPx, xdpi, ydpi)
        val s = session ?: createSession(screen)
        // Unfolded, or in a much bigger window: the lagoon widens to fill it. Not
        // when the same screen is turned sideways: then the camera just pans.
        val area = screen.widthMm * screen.heightMm
        if (area > largestScreenMm2 * GROW_AREA_RATIO) s.growTo(TankSize.forScreen(screen))
        largestScreenMm2 = maxOf(largestScreenMm2, area)
        val size = s.scene.value.size
        camera = (camera ?: Camera.initial(vp, size).let { c ->
            options.zoom?.let { c.zoomedAbout(vp.center, it, vp, size) } ?: c
        }).clamped(vp, size)
        pushCamera()
    }

    fun onSurfaceGone() {
        statsJob?.cancel()
        statsJob = null
        engine.setSurface(null)
    }

    private fun createSession(screen: ScreenMm): TankSession {
        val random = options.seed?.let { Random(it) } ?: Random.Default
        val size = TankSize.forScreen(screen)
        val saved = restored?.getOrNull()
        val ids = EntityIdAllocator(0)
        val scene = saved
            ?: options.population?.let { Population.sceneOf(it, size, ids, random) }?.let { sc ->
                // A close look: just these creatures, on just these decorations (or bare sand).
                val kinds = options.decor.orEmpty().flatMap { (id, n) -> List(n) { DecorCatalog.require(id) } }
                sc.copy(decor = DecorLayout.around(kinds, size, ids, random))
            }
            ?: Population.defaultScene(size, ids, random).let { sc ->
                options.decor?.let { d ->
                    val kinds = d.flatMap { (id, n) -> List(n) { DecorCatalog.require(id) } }
                    sc.copy(decor = DecorLayout.around(kinds, size, ids, random))
                } ?: sc
            }
        val s = TankSession(scene, LoopbackLink(), random)
        session = s
        sync.apply(scene)
        viewModelScope.launch { s.run() }
        viewModelScope.launch {
            s.scene.collect { sc ->
                sync.apply(sc)
                state.update {
                    it.copy(
                        ready = true,
                        counts = SpeciesCatalog.all.associate { sp -> sp.id to sc.count(sp.id) },
                        total = sc.creatures.size,
                        roomLeft = TankScene.MAX_CREATURES - sc.creatures.size,
                        tankSize = sc.size,
                        decorCounts = DecorCatalog.all.associate { k -> k.id to sc.countDecor(k.id) },
                        decorRoomLeft = TankScene.MAX_DECOR - sc.decor.size,
                    )
                }
                if (selected != null && sc.decor(selected!!) == null) selected = null
                refreshSelection()
            }
        }
        viewModelScope.launch {
            s.undo.collect { u -> state.update { it.copy(edit = it.edit.copy(undo = u)) } }
        }
        if (options.persist) {
            // Saved a moment after every change; [saveNow] catches the creatures' places on leaving.
            @OptIn(FlowPreview::class)
            viewModelScope.launch { s.scene.drop(1).debounce(SAVE_DEBOUNCE_MS).collect { save() } }
        }
        return s
    }

    /**
     * Saves the lagoon now, every creature where it is this moment. Called as
     * the app goes to the background, which may be just before it is
     * destroyed: the places are read at once, and the writing is not
     * cancelled with the view model.
     */
    fun saveNow() {
        if (!options.persist) return
        val s = session ?: return
        val scene = s.scene.value.withPlaces(places())
        viewModelScope.launch(NonCancellable) { lagoonStore.save(scene) }
    }

    private suspend fun save() {
        val s = session ?: return
        val places = withContext(background) { places() }
        lagoonStore.save(s.scene.value.withPlaces(places))
    }

    private fun places() = runCatching { engine.snapshot() }.getOrDefault(emptyList())
        .associate { it.id to CreaturePlace(Vec2(it.x, it.y), it.z) }

    // ------------------------------------------------------------ gestures

    fun pan(deltaPx: Vec2) {
        val vp = viewport ?: return
        val size = session?.scene?.value?.size ?: return
        camera = camera?.panned(deltaPx, vp, size)
        pushCamera()
        dismissHint()
    }

    fun zoom(focusPx: Vec2, factor: Float) {
        val vp = viewport ?: return
        val size = session?.scene?.value?.size ?: return
        camera = camera?.zoomedAbout(focusPx, factor, vp, size)
        pushCamera()
        dismissHint()
    }

    /** A tap on the glass: a ripple, and the fish nearby dart away. Two quick taps zoom. */
    fun tap(screenPx: Vec2) {
        val vp = viewport ?: return
        val cam = camera ?: return
        if (state.value.edit.active) {
            when (state.value.edit.tool) {
                EditTool.MOVE -> select(decorAt(screenPx)?.id)
                EditTool.NET -> sweepNet(screenPx)
            }
            return
        }
        engine.tap(cam.screenToWorld(screenPx, 0f, vp))
        val now = signals.nowMs()
        val previous = lastTap
        if (previous != null && now - previous.first < DOUBLE_TAP_MS && (screenPx - previous.second).length < DOUBLE_TAP_PX) {
            val target = if (cam.zoom < 1.5f) 2f else 1f
            zoom(screenPx, target / cam.zoom)
            lastTap = null
        } else {
            lastTap = now to screenPx
        }
        dismissHint()
    }

    /**
     * A finger dragging over the water sprinkles flakes along its path: a pinch
     * where it lands, then one flake every [SPRINKLE_SPACING_MM] of the way.
     */
    fun sprinkle(screenPx: Vec2, first: Boolean) {
        if (state.value.edit.active) {
            // Arranging, a finger dragging over open water pans instead of feeding.
            val last = lastPanPx
            if (!first && last != null) pan(screenPx - last)
            lastPanPx = screenPx
            return
        }
        val vp = viewport ?: return
        val cam = camera ?: return
        val at = cam.screenToWorld(screenPx, 0f, vp)
        val previous = lastSprinkle
        if (first || previous == null) {
            engine.dropFood(at, FoodKind.FLAKE, FIRST_PINCH)
            sprinkleCarry = 0f
        } else {
            sprinkleCarry += (at - previous).length
            val n = (sprinkleCarry / SPRINKLE_SPACING_MM).toInt()
            if (n > 0) {
                engine.dropFood(at, FoodKind.FLAKE, n.coerceAtMost(MAX_FLAKES_PER_MOVE))
                sprinkleCarry -= n * SPRINKLE_SPACING_MM
            }
        }
        lastSprinkle = at
        dismissHint()
    }

    fun endSprinkle() {
        lastSprinkle = null
        lastPanPx = null
    }

    /** A long press drops a pellet: it sinks fast, and the turtle loves it. */
    fun dropPellet(screenPx: Vec2) {
        if (state.value.edit.active) return
        val vp = viewport ?: return
        val cam = camera ?: return
        engine.dropFood(cam.screenToWorld(screenPx, 0f, vp), FoodKind.PELLET, 1)
        dismissHint()
    }

    fun dismissHint() {
        if (state.value.hintVisible) state.update { it.copy(hintVisible = false) }
    }

    private fun pushCamera() {
        val cam = camera ?: return
        val vp = viewport ?: return
        engine.setCamera(cam, vp)
        state.update { it.copy(zoom = cam.zoom) }
        refreshSelection()
    }

    // ------------------------------------------------------------ arranging

    fun setEditing(on: Boolean) {
        if (!on) finishGesture()
        if (!on) selected = null
        state.update { it.copy(edit = it.edit.copy(active = on, tool = if (on) it.edit.tool else EditTool.MOVE)) }
        refreshSelection()
        if (on) dismissHint()
    }

    fun setTool(tool: EditTool) {
        if (tool == EditTool.NET) selected = null
        state.update { it.copy(edit = it.edit.copy(tool = tool)) }
        refreshSelection()
    }

    fun undo() {
        val s = session ?: return
        viewModelScope.launch { s.undo() }
    }

    fun redo() {
        val s = session ?: return
        viewModelScope.launch { s.redo() }
    }

    /** Puts a new decoration where the camera is looking, selected, and starts arranging. */
    fun placeDecor(kindId: String) {
        val s = session ?: return
        val at = camera?.center ?: s.scene.value.size.center
        viewModelScope.launch {
            val d = s.placeDecor(kindId, at) ?: return@launch
            selected = d.id
            state.update { it.copy(edit = it.edit.copy(active = true, tool = EditTool.MOVE)) }
            refreshSelection()
        }
    }

    /** The decoration under a point of the screen, the tallest if several overlap. */
    fun decorAt(screenPx: Vec2): DecorSpec? {
        val vp = viewport ?: return null
        val cam = camera ?: return null
        val s = session ?: return null
        val depth = s.scene.value.size.depthMm
        val slop = TOUCH_SLOP_PX / cam.pxPerMm(vp)
        return s.scene.value.decor.sortedByDescending { it.heightMm }.firstOrNull { d ->
            // Seen from above, a decoration covers its footprint and, nearer the camera, its top.
            val onFoot = d.covers(cam.screenToWorld(screenPx, depth, vp), slop)
            val onTop = d.covers(cam.screenToWorld(screenPx, depth - d.heightMm, vp), slop)
            onFoot || onTop
        }
    }

    private fun select(id: EntityId?) {
        selected = id
        refreshSelection()
    }

    /** A drag starts: true if it picked up a decoration (it then follows the finger). */
    fun beginDrag(screenPx: Vec2): Boolean {
        if (!state.value.edit.active || state.value.edit.tool != EditTool.MOVE) return false
        val d = decorAt(screenPx) ?: return false
        val vp = viewport ?: return false
        val cam = camera ?: return false
        selected = d.id
        dragBefore = d
        dragOffset = d.at - cam.screenToWorld(screenPx, session!!.scene.value.size.depthMm, vp)
        refreshSelection()
        return true
    }

    fun drag(screenPx: Vec2) {
        val before = dragBefore ?: return
        val vp = viewport ?: return
        val cam = camera ?: return
        val s = session ?: return
        val at = cam.screenToWorld(screenPx, s.scene.value.size.depthMm, vp) + dragOffset
        val angle = s.scene.value.decor(before.id)?.angle ?: before.angle
        viewModelScope.launch { s.dragDecor(before.id, at, angle) }
    }

    /** Two fingers twisting turn the selected decoration. */
    fun rotateSelected(radians: Float) {
        if (!state.value.edit.active) return
        val s = session ?: return
        val d = selected?.let { s.scene.value.decor(it) } ?: return
        if (dragBefore == null) dragBefore = d
        viewModelScope.launch { s.dragDecor(d.id, d.at, d.angle + radians) }
    }

    /** The fingers are off the glass: a drag or a twist becomes one undoable move. */
    fun finishGesture() {
        val before = dragBefore ?: return
        dragBefore = null
        val s = session ?: return
        viewModelScope.launch {
            // After the moves already sent have come back.
            kotlinx.coroutines.yield()
            s.endDrag(before)
        }
    }

    /** The "turn" button: a twelfth of a turn, one undoable move. */
    fun turnSelected() {
        val s = session ?: return
        val d = selected?.let { s.scene.value.decor(it) } ?: return
        viewModelScope.launch {
            s.dragDecor(d.id, d.at, d.angle + (PI / 6).toFloat())
            kotlinx.coroutines.yield()
            s.endDrag(d)
        }
    }

    fun removeSelected() {
        val s = session ?: return
        val id = selected ?: return
        selected = null
        refreshSelection()
        viewModelScope.launch { s.removeDecor(id) }
    }

    /**
     * The net sweeps where the finger tapped and takes the creature nearest
     * the middle of its sweep, seen from above at whatever depth it swims.
     */
    private fun sweepNet(screenPx: Vec2) {
        val vp = viewport ?: return
        val cam = camera ?: return
        val s = session ?: return
        val pxPerMm = cam.pxPerMm(vp)
        val radiusPx = max(NET_RADIUS_PX, 6f * pxPerMm)
        viewModelScope.launch {
            val now = withContext(background) { runCatching { engine.snapshot() }.getOrDefault(emptyList()) }
            val scene = s.scene.value
            val best = now.mapNotNull { c ->
                val spec = scene.creature(c.id) ?: return@mapNotNull null
                val onScreen = cam.worldToScreen(Vec2(c.x, c.y), c.z, vp)
                val reach = radiusPx + spec.lengthMm * 0.4f * pxPerMm * DepthProjection.DEFAULT.scaleAt(c.z)
                val d = (onScreen - screenPx).length
                if (d <= reach) Triple(c, spec, d / reach) else null
            }.minByOrNull { it.third }
            val caught = best?.let { (c, spec, _) ->
                if (s.net(c.id, Vec2(c.x, c.y), c.z)) SpeciesCatalog[spec.speciesId]?.displayName ?: "creature" else null
            }
            sweeps++
            state.update { it.copy(edit = it.edit.copy(sweep = NetSweep(sweeps, screenPx, radiusPx, caught))) }
        }
    }

    private fun refreshSelection() {
        val id = selected
        val s = session
        val vp = viewport
        val cam = camera
        val d = if (id != null && s != null) s.scene.value.decor(id) else null
        val sel = if (d != null && vp != null && cam != null && state.value.edit.active) {
            val depth = s!!.scene.value.size.depthMm - d.heightMm * 0.5f
            SelectionUi(
                id = d.id,
                centerPx = cam.worldToScreen(d.at, depth, vp),
                radiusPx = d.radiusMm * cam.pxPerMm(vp) * DepthProjection.DEFAULT.scaleAt(depth) * 1.12f,
                name = d.kind.displayName,
            )
        } else {
            null
        }
        if (sel != state.value.edit.selection) state.update { it.copy(edit = it.edit.copy(selection = sel)) }
    }

    // ------------------------------------------------------------ tank

    /** Releases a batch of a species where the camera is looking. */
    fun release(speciesId: String) {
        val s = session ?: return
        val species = SpeciesCatalog.require(speciesId)
        viewModelScope.launch { s.add(speciesId, species.addBatch, around = camera?.center) }
    }

    fun resetTank() {
        val s = session ?: return
        selected = null
        viewModelScope.launch { s.reset() }
    }

    // ------------------------------------------------------------ settings & quality

    fun setQualityMode(mode: QualityMode) {
        viewModelScope.launch { settingsStore.setQuality(mode) }
    }

    fun setShowStats(show: Boolean) {
        viewModelScope.launch { settingsStore.setShowStats(show) }
    }

    private fun applyQuality() {
        val tier = when {
            options.quality != null -> options.quality
            settings.quality is QualityMode.Fixed -> (settings.quality as QualityMode.Fixed).tier
            else -> governor.tier
        }
        engine.setQuality(tier)
        state.update { it.copy(tier = tier) }
    }

    private fun startStats() {
        if (statsJob != null) return
        statsJob = viewModelScope.launch {
            var lastGovernorMs = 0L
            while (isActive) {
                val stats = withContext(background) { engine.stats() }
                val gpu = if (state.value.gpu.isEmpty() && stats.vulkan == VulkanState.RENDERING) {
                    withContext(background) { engine.gpuName() }
                } else {
                    state.value.gpu
                }
                state.update { it.copy(stats = stats, vulkanUnsupported = stats.vulkan == VulkanState.UNSUPPORTED, gpu = gpu) }
                val now = signals.nowMs()
                if (now - lastGovernorMs >= GOVERNOR_PERIOD_MS) {
                    lastGovernorMs = now
                    onPerfSample(stats, now)
                }
                delay(STATS_PERIOD_MS)
            }
        }
    }

    /** One governor step. Public for tests. */
    fun onPerfSample(stats: EngineStats, nowMs: Long) {
        if (options.quality != null || settings.quality !is QualityMode.Auto) return
        if (stats.vulkan != VulkanState.RENDERING || stats.fps <= 0f) return
        val headroom = signals.thermalHeadroom()
        val ignored = signals.thermalIsSimulated
        state.update { it.copy(thermalHeadroom = headroom, thermalIgnored = ignored) }
        // The first seconds include pipeline creation and shader warm-up: not a verdict on the device.
        val start = firstRenderMs ?: nowMs.also { firstRenderMs = it }
        if (nowMs - start < WARMUP_MS) return
        val before = governor.tier
        val sample = PerfSample(nowMs, stats.fps, signals.refreshRateHz(), stats.workMs, if (ignored) null else headroom)
        if (governor.onSample(sample) != before) applyQuality()
    }

    override fun onCleared() {
        engine.close()
    }

    companion object {
        const val HINT_MS = 7_000L
        const val STATS_PERIOD_MS = 500L
        const val GOVERNOR_PERIOD_MS = 2_000L
        /** A window this much larger than any before (unfolding) widens the lagoon. */
        const val GROW_AREA_RATIO = 1.3f
        const val WARMUP_MS = 6_000L
        const val SPRINKLE_SPACING_MM = 3.5f
        const val FIRST_PINCH = 3
        const val MAX_FLAKES_PER_MOVE = 6
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_PX = 60f
        const val SAVE_DEBOUNCE_MS = 1_500L
        /** How far from a decoration's outline a finger still picks it up. */
        const val TOUCH_SLOP_PX = 24f
        /** The net's sweep, at least this wide on screen. */
        const val NET_RADIUS_PX = 70f
    }
}
