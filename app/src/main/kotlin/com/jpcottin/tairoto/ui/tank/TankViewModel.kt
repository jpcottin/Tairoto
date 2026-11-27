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
import com.jpcottin.tairoto.engine.EngineSim
import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.NearbyLagoon
import com.jpcottin.tairoto.share.RelayAddress
import com.jpcottin.tairoto.share.ShareConnection
import com.jpcottin.tairoto.share.ShareConnector
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.world.DeviceId
import com.jpcottin.tairoto.world.EdgeSwipes
import com.jpcottin.tairoto.world.Turns
import com.jpcottin.tairoto.world.Visitors
import com.jpcottin.tairoto.world.Layout
import com.jpcottin.tairoto.world.RectMm
import com.jpcottin.tairoto.world.ShareEvent
import com.jpcottin.tairoto.world.ShareStatus
import com.jpcottin.tairoto.world.SharedTank
import com.jpcottin.tairoto.world.SimInput
import com.jpcottin.tairoto.world.Take
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
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
    /** The lagoon shared with other devices, or not. */
    val share: ShareUi = ShareUi(),
)

enum class SharePhase { OFF, CONNECTING, SHARED }

/** Hosting: the lagoon is this device's and the others join it. Guest: in another device's lagoon. */
enum class ShareRole { HOST, GUEST }

/** One screen of a shared lagoon, where it lies on the table. */
data class ShareDeviceUi(
    val name: String,
    val rect: RectMm,
    val self: Boolean,
    val host: Boolean,
    val id: DeviceId = DeviceId.Solo,
    /** Quarter turns clockwise of the device on the table (see [com.jpcottin.tairoto.world.Turns]). */
    val turns: Int = 0,
)

data class ShareUi(
    val phase: SharePhase = SharePhase.OFF,
    val role: ShareRole? = null,
    val devices: List<ShareDeviceUi> = emptyList(),
    /** The shared tank, for drawing the table to scale. */
    val tank: TankSize? = null,
    val nearby: List<NearbyLagoon> = emptyList(),
    /** Where to find a relay (a computer, for emulators), as typed. */
    val relay: String = RelayAddress.EMULATOR_HOST.toString(),
    /** Something to tell: it could not connect, the lagoon ended. */
    val message: String? = null,
    /** False while this device has lost touch with the host (its creatures wait). */
    val connected: Boolean = true,
    val rttMs: Float? = null,
    /** Creatures swum here from other screens, and swum away. */
    val arrived: Int = 0,
    val left: Int = 0,
    /** Creatures this device simulates right now. */
    val here: Int = 0,
    /** May the app reach the local network (a runtime permission from Android 17)? */
    val localNetwork: Boolean = true,
    /** Someone is arranging the screens: each shows who it is, and a swipe across two lays them side by side. */
    val arranging: Boolean = false,
) {
    val hostName: String? get() = devices.firstOrNull { it.host }?.name
}

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
    private val connector: ShareConnector? = null,
    /** This device's name, as other devices of a shared lagoon see it. */
    private val deviceName: String = "This device",
    /** The frame around this screen, in mm: how far its picture sits from the next device's. */
    private val bezelMm: Float = ScreenMm.PHONE_BEZEL_MM,
    /** One line of JSON per thing that happens in a shared lagoon (handoffs, losses), for logs and tests. */
    private val netLog: (String) -> Unit = {},
    /** A monotonic clock in seconds, for the shared lagoon's timing. */
    private val clockS: () -> Double = { System.nanoTime() / 1e9 },
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

    // Sharing
    private var sessionJob: Job? = null
    private var shareJob: Job? = null
    private var connection: ShareConnection? = null
    private var shared: SharedTank? = null
    private var role: ShareRole? = null
    private var screenMm: ScreenMm? = null
    private var autoShared = false
    private var lastStatusLog = 0.0

    // Edit mode
    private var selected: EntityId? = null
    private var dragBefore: DecorSpec? = null
    private var dragOffset = Vec2.Zero
    private var lastPanPx: Vec2? = null
    private var sweeps = 0

    init {
        if (options.deterministic) engine.setDeterministic(true, options.seed ?: 7L)
        options.maxFps?.let { engine.setMaxFps(it) }
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
        val screen = ScreenMm.fromPixels(widthPx, heightPx, xdpi, ydpi, bezelMm)
        screenMm = screen
        val sh = shared
        if (sh != null) {
            // Shared: the table's layout sets the tank and where this screen looks.
            viewModelScope.launch { sh.screenChanged(screen) }
            applySharedCamera(sh.layout.value)
            return
        }
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
        autoShare()
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
        attach(s, persist = options.persist) { s.run() }
        return s
    }

    /**
     * Makes [s] the session the screen shows: [runner] applies what comes in
     * (a loopback, or a shared lagoon's coordinator). Whatever ran for the
     * session before stops.
     */
    private fun attach(s: TankSession, persist: Boolean, runner: suspend () -> Unit) {
        sessionJob?.cancel()
        val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        sessionJob = job
        session = s
        sync.apply(s.scene.value)
        viewModelScope.launch(job) { runner() }
        viewModelScope.launch(job) {
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
        viewModelScope.launch(job) {
            s.undo.collect { u -> state.update { it.copy(edit = it.edit.copy(undo = u)) } }
        }
        if (persist) {
            // Saved a moment after every change; [saveNow] catches the creatures' places on leaving.
            @OptIn(FlowPreview::class)
            viewModelScope.launch(job) { s.scene.drop(1).debounce(SAVE_DEBOUNCE_MS).collect { save() } }
        }
    }

    /**
     * Saves the lagoon now, every creature where it is this moment. Called as
     * the app goes to the background, which may be just before it is
     * destroyed: the places are read at once, and the writing is not
     * cancelled with the view model.
     */
    fun saveNow() {
        if (!options.persist || role == ShareRole.GUEST) return
        val s = session ?: return
        val scene = s.scene.value.withPlaces(places()).withoutVisitors()
        viewModelScope.launch(NonCancellable) { lagoonStore.save(scene) }
    }

    private suspend fun save() {
        if (role == ShareRole.GUEST) return // another device's lagoon: this one's own was saved on joining
        val s = session ?: return
        val places = withContext(background) { places() }
        lagoonStore.save(s.scene.value.withPlaces(places).withoutVisitors())
    }

    /** This device's own lagoon: what guests brought goes home with them, it is not saved here. */
    private fun TankScene.withoutVisitors() = copy(creatures = creatures.filter { it.home == null }, decor = decor.filter { it.home == null })

    private fun places() = runCatching { engine.snapshot() }.getOrDefault(emptyList())
        .associate { it.id to CreaturePlace(Vec2(it.x, it.y), it.z) }

    // ------------------------------------------------------------ gestures

    fun pan(deltaPx: Vec2) {
        if (shared != null) return // each screen is its own window onto the table
        val vp = viewport ?: return
        val size = session?.scene?.value?.size ?: return
        camera = camera?.panned(deltaPx, vp, size)
        pushCamera()
        dismissHint()
    }

    fun zoom(focusPx: Vec2, factor: Float) {
        if (shared != null) return // life size, so a fish keeps its size from screen to screen
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
        val at = cam.screenToWorld(screenPx, 0f, vp)
        engine.tap(at)
        shared?.let { sh -> viewModelScope.launch { sh.tapped(at) } }
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
        if (arrangingTable()) return // a finger swiped across screens lines them up, it does not feed
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
        if (state.value.edit.active || arrangingTable()) return
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

    // ------------------------------------------------------------ sharing

    /** Shares this lagoon: the other devices join it, and the fish swim from screen to screen. */
    fun shareLagoon(route: ShareRoute) {
        val c = connector ?: return
        val s = session ?: return
        val screen = screenMm ?: return
        if (shared != null || state.value.share.phase == SharePhase.CONNECTING) return
        setShare { it.copy(phase = SharePhase.CONNECTING, message = null) }
        shareJob = viewModelScope.launch {
            val conn = try {
                c.host(deviceName, route)
            } catch (e: Exception) {
                failShare(e, "Could not share the lagoon")
                return@launch
            }
            // The same lagoon, every creature where it swims, now on the network.
            val scene = s.scene.value.withPlaces(withContext(background) { places() })
            val random = options.seed?.let { Random(it + 1) } ?: Random.Default
            val ns = TankSession(scene, conn.link, random)
            val sh = SharedTank(
                ns, conn.link, EngineSim(engine, background), clockS,
                SharedTank.soloLayout(conn.link.self, screen, scene.size), owners = emptyList(), onEvent = ::logEvent,
            )
            engine.setFoodSlot(conn.link.self.slot)
            begin(conn, ns, sh, ShareRole.HOST)
        }
    }

    /** Joins another device's lagoon. This device's own is saved and comes back on leaving. */
    fun joinLagoon(target: JoinTarget) {
        val c = connector ?: return
        val screen = screenMm ?: return
        if (shared != null || state.value.share.phase == SharePhase.CONNECTING) return
        setShare { it.copy(phase = SharePhase.CONNECTING, message = null) }
        shareJob = viewModelScope.launch {
            val conn = try {
                c.join(deviceName, target)
            } catch (e: Exception) {
                failShare(e, "Could not join the lagoon")
                return@launch
            }
            val join = SharedTank.requestJoin(conn.link, screen, JOIN_TIMEOUT_MS, brings())
            if (join == null) {
                conn.close()
                failShare(null, "The lagoon did not answer")
                return@launch
            }
            saveNow()
            // This device's own lagoon stops feeding the engine before it is emptied for the shared one.
            sessionJob?.cancel()
            sync.reset()
            val random = options.seed?.let { Random(it + conn.link.self.slot) } ?: Random.Default
            val ns = TankSession(join.scene, conn.link, random)
            val sh = SharedTank(ns, conn.link, EngineSim(engine, background), clockS, join.layout, join.owners, onEvent = ::logEvent)
            engine.setFoodSlot(conn.link.self.slot)
            begin(conn, ns, sh, ShareRole.GUEST)
        }
    }

    private fun begin(conn: ShareConnection, ns: TankSession, sh: SharedTank, r: ShareRole) {
        connection = conn
        shared = sh
        role = r
        selected = null
        // What this device does not simulate arrives as a ghost of another device's.
        sync.simulatesHere = { sh.shouldSimulate(it) }
        if (options.trace) sh.trace = { line -> netLog(json("trace", "t" to sh.sharedNow(), "self" to sh.self.slot, "m" to line)) }
        attach(ns, persist = options.persist && r == ShareRole.HOST) { sh.run() }
        val job = sessionJob!!
        viewModelScope.launch(job) {
            sh.layout.collect { layout ->
                applySharedCamera(layout)
                setShare { it.copy(devices = devicesOnTable(layout, sh.self), tank = layout.tank, arranging = layout.arranging) }
            }
        }
        viewModelScope.launch(job) {
            var lostSince: Double? = null
            sh.status.collect { st ->
                setShare {
                    it.copy(
                        phase = SharePhase.SHARED, role = r, connected = st.connected, rttMs = st.rttMs,
                        arrived = st.handoffsIn, left = st.handoffsOut, here = st.owned,
                    )
                }
                val now = clockS()
                if (now - lastStatusLog >= STATUS_LOG_S) {
                    lastStatusLog = now
                    logStatus(sh, st)
                }
                if (st.gone) {
                    endShare("The lagoon went on without this device")
                } else if (!st.connected && r == ShareRole.GUEST) {
                    val since = lostSince ?: now.also { lostSince = it }
                    if (now - since > GIVE_UP_S) endShare("Lost touch with ${state.value.share.hostName ?: "the lagoon"}")
                } else {
                    lostSince = null
                }
            }
        }
        viewModelScope.launch(job) {
            conn.ended.collect { reason ->
                endShare(if (r == ShareRole.GUEST) "${state.value.share.hostName ?: "The host"} stopped sharing" else "Sharing stopped: $reason")
            }
        }
        if (options.arrange && r == ShareRole.HOST) viewModelScope.launch(job) { sh.setArranging(true) }
        netLog(json("share", "role" to r.name.lowercase(), "self" to sh.self.slot, "name" to sh.self.name, "t" to sh.sharedNow()))
        // Who is in the lagoon as it starts here, so a log can account for every one of them.
        netLog(json("scene", "self" to sh.self.slot, "ids" to ns.scene.value.creatures.joinToString(",", "[", "]") { it.id.raw.toString() }))
    }

    /** The screens on the table; two devices of the same name (two of one model) are told apart by number. */
    private fun devicesOnTable(layout: Layout, self: DeviceId): List<ShareDeviceUi> {
        val names = layout.placements.groupingBy { it.device.name }.eachCount()
        return layout.placements.map { p ->
            val name = p.device.name.replace('_', ' ')
            ShareDeviceUi(
                name = if ((names[p.device.name] ?: 0) > 1) "$name ${p.device.slot + 1}" else name,
                rect = p.rect,
                self = p.device == self,
                host = p.device.slot == SharedTank.HOST_SLOT,
                id = p.device,
                turns = p.turns,
            )
        }
    }

    // ------------------------------------------------------------ the table

    private fun arrangingTable() = shared != null && state.value.share.arranging

    /** Lays [device]'s screen on the table at [center] (mm), turned [turns]: from the map, on any device. */
    fun arrangeScreen(device: DeviceId, center: Vec2, turns: Int) {
        val sh = shared ?: return
        viewModelScope.launch { sh.arrange(device, center, turns) }
    }

    /** Starts or ends arranging the screens, on every device. */
    fun setArranging(on: Boolean) {
        val sh = shared ?: return
        viewModelScope.launch { sh.setArranging(on) }
    }

    /** A finger began to move from [start]: while arranging, did it come onto this screen over an edge? */
    fun strokeStart(start: Vec2, toward: Vec2) {
        if (!arrangingTable()) return
        val k = glassPxPerMm() ?: return
        val vp = viewport ?: return
        val hit = EdgeSwipes.entering(start, toward, vp.widthPx, vp.heightPx, EdgeSwipes.MARGIN_MM * k) ?: return
        swipe(hit, entering = true, k)
    }

    /** A finger was lifted at [end]: while arranging, did it leave this screen over an edge? */
    fun strokeEnd(from: Vec2, end: Vec2) {
        if (!arrangingTable()) return
        val k = glassPxPerMm() ?: return
        val vp = viewport ?: return
        val hit = EdgeSwipes.leaving(from, end, vp.widthPx, vp.heightPx, EdgeSwipes.MARGIN_MM * k) ?: return
        swipe(hit, entering = false, k)
    }

    private fun swipe(hit: EdgeSwipes.AtEdge, entering: Boolean, pxPerMm: Float) {
        val sh = shared ?: return
        netLog(json("swipe", "self" to sh.self.slot, "t" to sh.sharedNow(), "edge" to hit.edge.name, "along" to hit.alongPx / pxPerMm, "entering" to entering))
        viewModelScope.launch { sh.edgeSwipe(hit.edge, hit.alongPx / pxPerMm, entering) }
    }

    /** Pixels per mm of glass, as the shared camera draws it (life size). */
    private fun glassPxPerMm(): Float? {
        val vp = viewport ?: return null
        return camera?.pxPerMm(vp)
    }

    /**
     * What this device's own lagoon brings to another's: every creature where
     * it swims and every decoration, from the middle of the screen. Its own
     * tank is bigger than its screen, so the places are drawn together (the
     * same way for all, keeping how they lie) to come in under its screen.
     * Only what is its own: visitors of a lagoon it hosted went home.
     */
    private suspend fun brings(): Visitors? {
        val s = session ?: return null
        val cam = camera ?: return null
        val screen = screenMm ?: return null
        val scene = s.scene.value.withPlaces(withContext(background) { places() }).withoutVisitors()
        val half = Vec2(screen.widthMm * 0.45f, screen.heightMm * 0.45f)
        fun underScreen(points: List<Vec2>): List<Vec2> {
            val rel = points.map { Turns.toScreen(it - cam.center, cam.turns) }
            val k = rel.fold(1f) { k, p -> minOf(k, half.x / maxOf(kotlin.math.abs(p.x), 1e-3f), half.y / maxOf(kotlin.math.abs(p.y), 1e-3f)) }
            return rel.map { it * k }
        }
        val creatures = underScreen(scene.creatures.map { it.spawnAt })
        val decor = underScreen(scene.decor.map { it.at })
        return Visitors(
            creatures = scene.creatures.mapIndexed { i, c -> c.copy(spawnAt = creatures[i]) },
            decor = scene.decor.mapIndexed { i, d -> d.copy(at = decor[i]) },
        ).takeIf { !it.isEmpty }
    }

    /** Stops sharing: a guest goes back to its own lagoon, a host keeps its lagoon with every creature in it. */
    fun stopSharing() {
        if (shared == null) {
            shareJob?.cancel()
            setShare { it.copy(phase = SharePhase.OFF) }
            return
        }
        viewModelScope.launch { finish(graceful = true, message = null) }
    }

    /**
     * The app went to the background. Its lagoon only swims while it is on
     * screen, so a shared one would leave its creatures frozen: it leaves
     * instead, handing them over (a host ends the lagoon for everyone).
     */
    fun onBackground() {
        if (shared != null) stopSharing()
    }

    private fun endShare(message: String) {
        if (shared == null) return
        viewModelScope.launch { finish(graceful = false, message = message) }
    }

    private suspend fun finish(graceful: Boolean, message: String?) {
        val sh = shared ?: return
        val conn = connection
        val r = role
        shared = null
        connection = null
        role = null
        sessionJob?.cancel()
        if (graceful) {
            runCatching { sh.leave() }
            delay(GOODBYE_MS) // the goodbye goes out before the connection closes
        }
        conn?.close()
        netLog(json("left", "self" to sh.self.slot, "why" to (message ?: "stopped")))
        sync.simulatesHere = { true }
        engine.setFoodSlot(0)
        val screen = screenMm
        val vp = viewport
        if (r == ShareRole.HOST) {
            // Everything in the lagoon is this device's again, where it swims; what guests brought went home.
            val scene = sh.session.scene.value.withoutVisitors()
            withContext(background) { engine.exchange(SimInput(take = scene.creatures.map { Take(it.id, null) })) }
            val places = withContext(background) { places() }
            val random = options.seed?.let { Random(it + 2) } ?: Random.Default
            val s = TankSession(scene.withPlaces(places), LoopbackLink(), random)
            attach(s, persist = options.persist) { s.run() }
        } else {
            // Back to this device's own lagoon, as it was saved on joining.
            sync.reset()
            session = null
            restored = if (options.persist) Result.success(runCatching { lagoonStore.load() }.getOrNull()) else Result.success(null)
            if (screen != null) createSession(screen)
        }
        camera = null
        setShare { ShareUi(relay = it.relay, message = message) }
        if (vp != null && screen != null) {
            val size = session?.scene?.value?.size
            if (size != null) {
                camera = Camera.initial(vp, size)
                pushCamera()
            }
        }
    }

    private fun failShare(e: Exception?, what: String) {
        val why = e?.message?.takeIf { it.isNotBlank() }
        setShare { it.copy(phase = SharePhase.OFF, message = if (why != null) "$what: $why" else what) }
    }

    /** Starts looking for lagoons shared nearby (while the share sheet is open). */
    fun lookForLagoons(on: Boolean) {
        val c = connector ?: return
        if (on) {
            c.discover()
            if (nearbyJob == null) nearbyJob = viewModelScope.launch { c.nearby.collect { n -> setShare { it.copy(nearby = n) } } }
        } else {
            c.stopDiscovery()
            nearbyJob?.cancel()
            nearbyJob = null
        }
    }

    private var nearbyJob: Job? = null

    fun setRelayAddress(text: String) = setShare { it.copy(relay = text) }

    /** Whether the local network may be reached; without it, nothing can be shared. */
    fun setLocalNetworkAllowed(allowed: Boolean) {
        setShare { it.copy(localNetwork = allowed) }
        if (!allowed) lookForLagoons(false)
    }

    fun localNetworkRefused() = setShare {
        it.copy(localNetwork = false, message = "Sharing needs access to the local network: allow it in the app's settings.")
    }

    fun dismissShareMessage() = setShare { it.copy(message = null) }

    private fun setShare(f: (ShareUi) -> ShareUi) = state.update { it.copy(share = f(it.share)) }

    /** Shared, the camera looks life size at this screen's place on the table, around the parallax point all share. */
    private fun applySharedCamera(layout: Layout) {
        val vp = viewport ?: return
        val sh = shared ?: return
        val place = layout.placement(sh.self) ?: return
        val rect = place.rect
        // Turned on the table, the glass's width runs along the table's height: life size either way.
        val cam = Camera(
            center = rect.center, zoom = vp.widthPx / (vp.basePxPerMm * place.screen.widthMm),
            parallaxOrigin = layout.origin, turns = place.turns,
        )
        camera = cam
        pushCamera()
        // What this screen shows of the table, and what lies on its floor: logs from every device must agree.
        val scene = session?.scene?.value
        netLog(
            json(
                "view", "self" to sh.self.slot, "t" to sh.sharedNow(),
                "rect" to "[${rect.x},${rect.y},${rect.w},${rect.h}]", "turns" to place.turns,
                "center" to "[${cam.center.x},${cam.center.y}]", "origin" to "[${cam.origin.x},${cam.origin.y}]",
                "pxPerMm" to cam.pxPerMm(vp), "vp" to "[${vp.widthPx},${vp.heightPx}]",
                "tank" to (scene?.size?.let { "[${it.widthMm},${it.heightMm},${it.depthMm}]" } ?: "[]"),
                "decor" to (scene?.decor?.joinToString(",", "[", "]") { "[${it.id.raw},\"${it.kindId}\",${it.at.x},${it.at.y},${it.angle}]" } ?: "[]"),
            ),
        )
    }

    /** Shares or joins as the launch asked (tests across emulators), once the lagoon is up. */
    private fun autoShare() {
        val launch = options.share ?: return
        if (autoShared) return
        autoShared = true
        // "wifi" or "bluetooth" instead of an address: nearby, as from the share sheet.
        val nearbyVia = when (launch.relay?.lowercase()) {
            "wifi" -> NearbyLagoon.Via.WIFI
            "bluetooth" -> NearbyLagoon.Via.BLUETOOTH
            else -> null
        }
        val relay = (if (nearbyVia == null) RelayAddress.parse(launch.relay) else null) ?: RelayAddress.EMULATOR_HOST
        if (nearbyVia == null) setShare { it.copy(relay = relay.toString()) }
        if (launch.host) {
            shareLagoon(if (nearbyVia != null) ShareRoute.Nearby else ShareRoute.Relay(relay, launch.session))
        } else {
            if (nearbyVia != null) lookForLagoons(true)
            // The host may not be up yet: try for a while.
            viewModelScope.launch {
                val until = clockS() + AUTO_JOIN_S
                while (shared == null && clockS() < until) {
                    if (state.value.share.phase == SharePhase.OFF) {
                        val target = if (nearbyVia == null) {
                            JoinTarget.Relay(relay, launch.session)
                        } else {
                            connector?.nearby?.value?.firstOrNull { it.via == nearbyVia }?.let { JoinTarget.Nearby(it) }
                        }
                        if (target != null) joinLagoon(target)
                    }
                    delay(AUTO_JOIN_RETRY_MS)
                }
            }
        }
    }

    private fun logEvent(e: ShareEvent) {
        val self = shared?.self?.slot ?: -1
        netLog(
            when (e) {
                is ShareEvent.Took -> json("took", "t" to e.t, "self" to self, "id" to e.id.raw, "epoch" to e.epoch)
                is ShareEvent.Released -> json("released", "t" to e.t, "self" to self, "id" to e.id.raw, "to" to (e.to?.slot ?: -1))
                is ShareEvent.FoodSent -> json("food_sent", "t" to e.t, "self" to self, "id" to e.id, "to" to e.to.slot, "mg" to e.mg)
                is ShareEvent.FoodTaken -> json("food_taken", "t" to e.t, "self" to self, "id" to e.id, "mg" to e.mg)
                is ShareEvent.DeviceLost -> json("lost", "t" to e.t, "self" to self, "device" to e.device.slot, "taken" to e.taken)
                is ShareEvent.Fenced -> json("fenced", "t" to e.t, "self" to self, "fenced" to e.fenced)
                is ShareEvent.Stitched -> json("stitched", "t" to e.t, "self" to self, "from" to e.from.slot, "to" to e.to.slot)
            },
        )
    }

    private fun logStatus(sh: SharedTank, st: ShareStatus) {
        // What the scene says lies on the floor, against what the engine draws: they must be the same.
        val synced = sync.describeDecor()
        val t = sh.sharedNow()
        viewModelScope.launch {
            val drawn = withContext(background) { engine.describeDecor() }
            netLog(json("floor", "t" to t, "self" to sh.self.slot, "scene" to synced, "engine" to drawn))
        }
        val l = sh.ledger
        netLog(
            json(
                "status", "t" to sh.sharedNow(), "self" to sh.self.slot, "devices" to st.devices, "owned" to st.owned,
                "creatures" to sh.session.scene.value.creatures.size,
                "bytesIn" to (connection?.bytesIn ?: 0L), "bytesOut" to (connection?.bytesOut ?: 0L),
                "in" to st.handoffsIn, "out" to st.handoffsOut, "connected" to st.connected,
                "rtt" to (st.rttMs ?: -1f), "dropped" to l.dropped, "eaten" to l.eaten, "decomposed" to l.decomposed,
                "sent" to l.sent, "received" to l.received, "water" to l.inWater,
            ),
        )
    }

    private fun json(event: String, vararg fields: Pair<String, Any>): String = buildString {
        append("{\"ev\":\"").append(event).append('"')
        for ((k, v) in fields) {
            append(",\"").append(k).append("\":")
            when (v) {
                is String -> if (v.startsWith("[")) append(v) else append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                is Double -> append(String.format(Locale.US, "%.4f", v))
                is Float -> append(String.format(Locale.US, "%.4f", v))
                else -> append(v)
            }
        }
        append('}')
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
        connection?.close()
        connector?.stopDiscovery()
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
        const val JOIN_TIMEOUT_MS = 8_000L
        const val GOODBYE_MS = 150L
        /** A guest out of touch with the host this long gives up and goes back to its own lagoon. */
        const val GIVE_UP_S = 6.0
        const val STATUS_LOG_S = 2.0
        const val AUTO_JOIN_S = 90.0
        const val AUTO_JOIN_RETRY_MS = 2_000L
        /** How far from a decoration's outline a finger still picks it up. */
        const val TOUCH_SLOP_PX = 24f
        /** The net's sweep, at least this wide on screen. */
        const val NET_RADIUS_PX = 70f
    }
}
