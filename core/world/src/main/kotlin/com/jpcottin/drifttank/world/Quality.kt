package com.jpcottin.drifttank.world

/**
 * How much the renderer does. Mirrored by `Quality` in render/quality.h; the
 * native side reads [nativeId] and derives its settings from it.
 */
enum class QualityTier(
    val nativeId: Int,
    val label: String,
    /** Offscreen scene resolution relative to the screen. */
    val renderScale: Float,
    /** Particles of marine snow in the water. */
    val motes: Int,
    /** Animated surface refraction and glints in the composite pass. */
    val refraction: Boolean,
) {
    LOW(0, "Low", renderScale = 0.6f, motes = 160, refraction = false),
    HIGH(1, "High", renderScale = 0.85f, motes = 420, refraction = true),
    ULTRA(2, "Ultra", renderScale = 1f, motes = 720, refraction = true),

    /** For tests and screenshots: a fixed time step and a fixed seed, so a frame is the same every run. */
    CI(3, "CI", renderScale = 0.5f, motes = 300, refraction = true),
    ;

    companion object {
        /** The tiers the governor moves between, cheapest first. */
        val ladder = listOf(LOW, HIGH, ULTRA)

        fun fromNativeId(id: Int): QualityTier = entries.firstOrNull { it.nativeId == id } ?: HIGH
    }
}

/** What the user picked in settings. */
sealed interface QualityMode {
    data object Auto : QualityMode
    data class Fixed(val tier: QualityTier) : QualityMode
}

/**
 * One look at how the device copes, taken every couple of seconds.
 *
 * @param fps frames shown per second over the window.
 * @param targetFps what the display asks for (60, 90, 120).
 * @param workMs mean CPU time per frame spent by the engine (simulation, meshes, recording).
 * @param thermalHeadroom PowerManager's forecast: 1.0 means throttling is about to start; null when unknown.
 */
data class PerfSample(
    val timeMs: Long,
    val fps: Float,
    val targetFps: Float,
    val workMs: Float,
    val thermalHeadroom: Float?,
)

/**
 * Picks a [QualityTier] in Auto mode: steps down quickly when frames are
 * dropped or the device is about to throttle, and steps up slowly, only after
 * a long run of easy frames, and never soon after a step down (so it does not
 * flip back and forth).
 */
class QualityGovernor(
    start: QualityTier = QualityTier.HIGH,
    private val downCooldownMs: Long = 4_000,
    private val upAfterDownMs: Long = 20_000,
    private val easySamplesToStepUp: Int = 5,
    private val slowSamplesToStepDown: Int = 2,
) {
    init {
        require(start in QualityTier.ladder) { "the governor only moves along the ladder, not to $start" }
    }

    var tier: QualityTier = start
        private set

    private var slowRun = 0
    private var easyRun = 0
    private var lastChangeMs = Long.MIN_VALUE / 2
    private var lastDownMs = Long.MIN_VALUE / 2

    /** Feeds one sample; returns the tier to use from now on. */
    fun onSample(s: PerfSample): QualityTier {
        val hot = (s.thermalHeadroom ?: 0f) >= HOT_HEADROOM
        val warm = (s.thermalHeadroom ?: 0f) >= WARM_HEADROOM
        val budgetMs = 1000f / s.targetFps.coerceAtLeast(1f)
        val dropping = s.fps < s.targetFps * DROP_RATIO
        val easy = s.fps >= s.targetFps * EASY_FPS_RATIO && s.workMs < budgetMs * EASY_WORK_RATIO

        slowRun = if (dropping || hot) slowRun + 1 else 0
        easyRun = if (easy && !warm) easyRun + 1 else 0

        val index = QualityTier.ladder.indexOf(tier)
        val sinceChange = s.timeMs - lastChangeMs
        when {
            (hot || slowRun >= slowSamplesToStepDown) && index > 0 && sinceChange >= downCooldownMs -> {
                tier = QualityTier.ladder[index - 1]
                lastChangeMs = s.timeMs
                lastDownMs = s.timeMs
                slowRun = 0
                easyRun = 0
            }
            easyRun >= easySamplesToStepUp && index < QualityTier.ladder.lastIndex &&
                s.timeMs - lastDownMs >= upAfterDownMs -> {
                tier = QualityTier.ladder[index + 1]
                lastChangeMs = s.timeMs
                easyRun = 0
            }
        }
        return tier
    }

    companion object {
        const val HOT_HEADROOM = 0.95f
        const val WARM_HEADROOM = 0.75f
        const val DROP_RATIO = 0.85f
        const val EASY_FPS_RATIO = 0.97f
        const val EASY_WORK_RATIO = 0.45f
    }
}

/** A running mean over the last [capacity] values, for frame times. */
class RollingMean(private val capacity: Int) {
    init {
        require(capacity > 0)
    }

    private val values = FloatArray(capacity)
    private var count = 0
    private var next = 0
    private var sum = 0.0

    val size: Int get() = count
    val mean: Float get() = if (count == 0) 0f else (sum / count).toFloat()

    fun add(v: Float) {
        if (count == capacity) sum -= values[next] else count++
        values[next] = v
        sum += v
        next = (next + 1) % capacity
    }
}
