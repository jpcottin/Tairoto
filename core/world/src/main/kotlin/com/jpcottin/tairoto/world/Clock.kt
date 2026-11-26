package com.jpcottin.tairoto.world

/**
 * The session's shared clock, estimated the NTP way: a device sends the host
 * a ping stamped with its own time, the host answers with its time, and
 * half the round trip is taken as the way back. Of the last few samples the
 * one with the shortest round trip wins: it had the least room for queueing
 * to skew it. On the host the shared clock is simply its own.
 */
class ClockSync(private val window: Int = 8) {
    private data class Sample(val offset: Double, val rtt: Double)

    private val samples = ArrayDeque<Sample>()

    /** Shared time minus local time, in seconds; 0 until the first answer. */
    var offsetS: Double = 0.0
        private set

    /** The round trip of the sample in use, in seconds (infinite before any). */
    var rttS: Double = Double.POSITIVE_INFINITY
        private set

    val synced: Boolean get() = samples.isNotEmpty()

    /** A pong: the ping left at [sentLocal], the host stamped it [hostTime], it came back at [nowLocal]. */
    fun onPong(sentLocal: Double, hostTime: Double, nowLocal: Double) {
        val rtt = nowLocal - sentLocal
        if (rtt < 0 || rtt.isNaN()) return
        samples.addLast(Sample(hostTime + rtt / 2 - nowLocal, rtt))
        while (samples.size > window) samples.removeFirst()
        val best = samples.minBy { it.rtt }
        offsetS = best.offset
        rttS = best.rtt
    }

    fun sharedTime(localS: Double): Double = localS + offsetS
}
