package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClockSyncTest {
    @Test fun `with an even round trip the offset is exact`() {
        val sync = ClockSync()
        // Local clock 100 s behind the host; 20 ms each way.
        sync.onPong(sentLocal = 1.0, hostTime = 101.02, nowLocal = 1.04)
        assertEquals(100.0, sync.offsetS, 1e-9)
        assertEquals(0.04, sync.rttS, 1e-9)
        assertEquals(105.0, sync.sharedTime(5.0), 1e-9)
    }

    @Test fun `the quickest round trip of the last few wins`() {
        val sync = ClockSync(window = 4)
        val rnd = Random(3)
        var local = 0.0
        val offset = -42.5
        repeat(40) {
            val out = 0.01 + rnd.nextDouble() * 0.2
            val back = 0.01 + rnd.nextDouble() * 0.2
            val hostAt = local + out + offset
            sync.onPong(local, hostAt, local + out + back)
            local += 0.5
        }
        // Each sample is off by at most half the asymmetry of its own round trip.
        assertEquals(offset, sync.offsetS, sync.rttS / 2)
        assertTrue(sync.rttS < 0.2)
    }

    @Test fun `nonsense is ignored`() {
        val sync = ClockSync()
        sync.onPong(sentLocal = 5.0, hostTime = 10.0, nowLocal = 4.0)
        assertTrue(!sync.synced)
        assertEquals(0.0, sync.offsetS, 0.0)
    }
}
