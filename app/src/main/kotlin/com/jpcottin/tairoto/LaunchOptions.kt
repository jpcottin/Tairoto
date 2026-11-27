package com.jpcottin.tairoto

import android.content.Intent
import com.jpcottin.tairoto.world.QualityTier

/**
 * How the app was started. Tests and screenshots launch with extras for a
 * frame that is the same every run:
 *
 *     adb shell am start -n com.jpcottin.tairoto/.MainActivity \
 *       --ez tairoto.ci true --el tairoto.seed 7
 *
 * Extras are read in debuggable builds only.
 */
data class LaunchOptions(
    val seed: Long? = null,
    /** Fixed time step, fixed seed, the CI quality tier and no hints. */
    val ci: Boolean = false,
    val quality: QualityTier? = null,
    val hints: Boolean = true,
    val stats: Boolean? = null,
    /** A lagoon of just these creatures (species id to count) instead of the default one. */
    val population: Map<String, Int>? = null,
    /** Starting zoom, for a close look (1 = the whole width of the lagoon). */
    val zoom: Float? = null,
    /** Start with a new lagoon and do not save it: the saved one is left as it was. */
    val fresh: Boolean = false,
    /** With [population], a floor of just these decorations (kind id to count) around the middle. */
    val decor: Map<String, Int>? = null,
    /** Share the lagoon, or join one, through a relay as soon as it is up (tests across emulators). */
    val share: ShareLaunch? = null,
    /** Log every ownership message of a shared lagoon (to chase a bug across devices). */
    val trace: Boolean = false,
    /** Hosting, start arranging the screens at once (tests swipe across emulators). */
    val arrange: Boolean = false,
    /** The sound's volume whatever the settings say (0 silent); a CI run is silent unless asked. */
    val sound: Float? = null,
    /** At most this many frames a second (tests on machines drawing in software). */
    val maxFps: Int? = null,
) {
    val deterministic: Boolean get() = ci

    /** A run for tests or a close look neither restores the saved lagoon nor overwrites it. */
    val persist: Boolean get() = !ci && !fresh && population == null && decor == null

    companion object {
        const val SEED = "tairoto.seed"
        const val CI = "tairoto.ci"
        const val QUALITY = "tairoto.quality"
        const val HINTS = "tairoto.hints"
        const val STATS = "tairoto.stats"
        /** For example `hermit_crab:3,day_octopus:1`. */
        const val POPULATION = "tairoto.population"
        const val ZOOM = "tairoto.zoom"
        const val FRESH = "tairoto.fresh"
        /** For example `coral_head:1,giant_clam:2`. */
        const val DECOR = "tairoto.decor"
        /** `host` or `join`: share the lagoon through the relay, or join the one shared there. */
        const val SHARE = "tairoto.share"
        /** The relay, `host:port` (default 10.0.2.2:7171, the computer an emulator runs on). */
        const val RELAY = "tairoto.relay"
        const val SESSION = "tairoto.session"
        const val TRACE = "tairoto.trace"
        const val ARRANGE = "tairoto.arrange"
        const val SOUND = "tairoto.sound"
        const val FPS = "tairoto.fps"

        /** Reads `kind:count,kind:count` of decorations; unknown or malformed entries are skipped. */
        fun parseDecor(text: String?): Map<String, Int>? {
            if (text.isNullOrBlank()) return null
            val out = linkedMapOf<String, Int>()
            for (part in text.split(',')) {
                val (id, n) = part.split(':').map { it.trim() }.takeIf { it.size == 2 } ?: continue
                val count = n.toIntOrNull()?.takeIf { it in 0..24 } ?: continue
                if (com.jpcottin.tairoto.world.DecorCatalog[id] != null) out[id] = count
            }
            return out.takeIf { it.isNotEmpty() }
        }

        /** Reads `id:count,id:count`; unknown or malformed entries are skipped. */
        fun parsePopulation(text: String?): Map<String, Int>? {
            if (text.isNullOrBlank()) return null
            val out = linkedMapOf<String, Int>()
            for (part in text.split(',')) {
                val (id, n) = part.split(':').map { it.trim() }.takeIf { it.size == 2 } ?: continue
                val count = n.toIntOrNull()?.takeIf { it in 0..200 } ?: continue
                if (com.jpcottin.tairoto.world.SpeciesCatalog[id] != null) out[id] = count
            }
            return out.takeIf { it.isNotEmpty() }
        }

        fun from(intent: Intent?, debuggable: Boolean): LaunchOptions {
            if (intent == null || !debuggable) return LaunchOptions()
            val ci = intent.getBooleanExtra(CI, false)
            return LaunchOptions(
                seed = if (intent.hasExtra(SEED)) intent.getLongExtra(SEED, 0L) else if (ci) 7L else null,
                ci = ci,
                quality = intent.getStringExtra(QUALITY)?.let { q -> QualityTier.entries.firstOrNull { it.name.equals(q, true) } }
                    ?: if (ci) QualityTier.CI else null,
                hints = intent.getBooleanExtra(HINTS, !ci),
                stats = if (intent.hasExtra(STATS)) intent.getBooleanExtra(STATS, false) else null,
                population = parsePopulation(intent.getStringExtra(POPULATION)),
                zoom = if (intent.hasExtra(ZOOM)) intent.getFloatExtra(ZOOM, 1f).takeIf { it in 0.5f..8f } else null,
                fresh = intent.getBooleanExtra(FRESH, false),
                decor = parseDecor(intent.getStringExtra(DECOR)),
                share = ShareLaunch.parse(intent.getStringExtra(SHARE), intent.getStringExtra(RELAY), intent.getStringExtra(SESSION)),
                trace = intent.getBooleanExtra(TRACE, false),
                arrange = intent.getBooleanExtra(ARRANGE, false),
                maxFps = if (intent.hasExtra(FPS)) intent.getIntExtra(FPS, 0).takeIf { it in 1..240 } else null,
                sound = if (intent.hasExtra(SOUND)) intent.getFloatExtra(SOUND, 0f).coerceIn(0f, 1f) else if (ci) 0f else null,
            )
        }
    }
}

/** A shared lagoon to start or join at launch. */
data class ShareLaunch(val host: Boolean, val relay: String?, val session: String) {
    companion object {
        fun parse(mode: String?, relay: String?, session: String?): ShareLaunch? {
            val host = when (mode?.trim()?.lowercase()) {
                "host" -> true
                "join" -> false
                else -> return null
            }
            return ShareLaunch(host, relay?.trim()?.takeIf { it.isNotEmpty() }, session?.trim()?.takeIf { it.isNotEmpty() } ?: "lagoon")
        }
    }
}
