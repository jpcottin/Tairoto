package com.jpcottin.tairoto.world

import kotlin.math.max

/**
 * The light over the lagoon at an hour of the day: [light] 0 under the moon
 * to 1 at full day, and [warmth] 0 to 1, the gold of the sun low at dawn and
 * dusk. Near the equator, as in Tahiti, the sun rises about six and sets
 * about six all year, and quickly.
 */
data class Daylight(val light: Float, val warmth: Float) {
    init {
        require(light in 0f..1f && warmth in 0f..1f)
    }

    val isNight: Boolean get() = light < 0.25f

    companion object {
        val NOON = Daylight(1f, 0f)
    }
}

object DayCycle {
    const val SUNRISE = 6.0f
    const val SUNSET = 18.1f
    /** Hours from the first light to the full day (and back at dusk). */
    const val TWILIGHT_H = 1.2f

    /** The light at [hours] past midnight, local time (0 until 24; outside, wrapped). */
    fun at(hours: Float): Daylight {
        val h = ((hours % 24f) + 24f) % 24f
        val rise = ramp(h, SUNRISE - TWILIGHT_H * 0.5f, SUNRISE + TWILIGHT_H * 0.5f)
        val set = 1f - ramp(h, SUNSET - TWILIGHT_H * 0.5f, SUNSET + TWILIGHT_H * 0.5f)
        val light = minOf(rise, set)
        // Gold around sunrise and sunset, gone an hour or so into the day or the night.
        val nearRise = 1f - minOf(1f, kotlin.math.abs(h - SUNRISE) / (TWILIGHT_H * 1.3f))
        val nearSet = 1f - minOf(1f, kotlin.math.abs(h - SUNSET) / (TWILIGHT_H * 1.3f))
        val warmth = max(nearRise, nearSet).let { it * it * (3f - 2f * it) }
        return Daylight(light.coerceIn(0f, 1f), warmth.coerceIn(0f, 1f))
    }

    private fun ramp(x: Float, a: Float, b: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** Which light the lagoon has: the hour where the device is, or always day, or always night. */
enum class TimeOfDay {
    LOCAL, DAY, NIGHT;

    /** The light now, [localHours] being the hour of the day where the device is. */
    fun daylight(localHours: Float): Daylight = when (this) {
        LOCAL -> DayCycle.at(localHours)
        DAY -> Daylight.NOON
        NIGHT -> Daylight(0f, 0f)
    }

    companion object {
        /** "local", "day", "night", as settings and launch options write it; null if none of them. */
        fun parse(text: String?): TimeOfDay? = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) }
    }
}
