package com.jpcottin.drifttank

import android.content.Intent
import com.jpcottin.drifttank.world.QualityTier

/**
 * How the app was started. Tests and screenshots launch with extras for a
 * frame that is the same every run:
 *
 *     adb shell am start -n com.jpcottin.drifttank/.MainActivity \
 *       --ez drifttank.ci true --el drifttank.seed 7
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
) {
    val deterministic: Boolean get() = ci

    companion object {
        const val SEED = "drifttank.seed"
        const val CI = "drifttank.ci"
        const val QUALITY = "drifttank.quality"
        const val HINTS = "drifttank.hints"
        const val STATS = "drifttank.stats"

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
            )
        }
    }
}
