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
) {
    val deterministic: Boolean get() = ci

    companion object {
        const val SEED = "tairoto.seed"
        const val CI = "tairoto.ci"
        const val QUALITY = "tairoto.quality"
        const val HINTS = "tairoto.hints"
        const val STATS = "tairoto.stats"
        /** For example `hermit_crab:3,day_octopus:1`. */
        const val POPULATION = "tairoto.population"
        const val ZOOM = "tairoto.zoom"

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
            )
        }
    }
}
