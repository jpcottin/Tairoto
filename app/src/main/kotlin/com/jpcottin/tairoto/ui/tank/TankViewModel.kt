package com.jpcottin.tairoto.ui.tank

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.data.AppSettings
import com.jpcottin.tairoto.data.SettingsStore
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.FoodKind
import com.jpcottin.tairoto.engine.SceneSync
import com.jpcottin.tairoto.engine.TankEngine
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.world.Camera
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
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.Viewport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    init {
        if (options.deterministic) engine.setDeterministic(true, options.seed ?: 7L)
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

    fun onSurfaceSize(widthPx: Int, heightPx: Int, xdpi: Float, ydpi: Float) {
        if (widthPx <= 0 || heightPx <= 0) return
        val vp = Viewport(widthPx.toFloat(), heightPx.toFloat(), ScreenMm.pxPerMm((xdpi + ydpi) / 2f))
        viewport = vp
        val s = session ?: createSession(ScreenMm.fromPixels(widthPx, heightPx, xdpi, ydpi))
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
        val scene = options.population?.let { Population.sceneOf(it, size, EntityIdAllocator(0), random) }
            ?: Population.defaultScene(size, EntityIdAllocator(0), random)
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
                    )
                }
            }
        }
        return s
    }

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
    }

    /** A long press drops a pellet: it sinks fast, and the turtle loves it. */
    fun dropPellet(screenPx: Vec2) {
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
        const val WARMUP_MS = 6_000L
        const val SPRINKLE_SPACING_MM = 3.5f
        const val FIRST_PINCH = 3
        const val MAX_FLAKES_PER_MOVE = 6
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_TAP_PX = 60f
    }
}
