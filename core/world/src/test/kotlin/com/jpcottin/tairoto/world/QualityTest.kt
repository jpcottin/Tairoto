package com.jpcottin.tairoto.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    private fun sample(t: Long, fps: Float = 60f, work: Float = 3f, headroom: Float? = 0.3f) =
        PerfSample(timeMs = t, fps = fps, targetFps = 60f, workMs = work, thermalHeadroom = headroom)

    @Test fun `tiers map to and from native ids`() {
        QualityTier.entries.forEach { assertEquals(it, QualityTier.fromNativeId(it.nativeId)) }
        assertEquals(QualityTier.HIGH, QualityTier.fromNativeId(99))
    }

    @Test fun `better tiers do more work`() {
        val l = QualityTier.ladder
        for (i in 1 until l.size) {
            assertTrue(l[i].renderScale > l[i - 1].renderScale)
            assertTrue(l[i].motes > l[i - 1].motes)
        }
    }

    @Test fun `dropped frames step down after two slow samples`() {
        val g = QualityGovernor(QualityTier.ULTRA)
        assertEquals(QualityTier.ULTRA, g.onSample(sample(0, fps = 40f)))
        assertEquals(QualityTier.HIGH, g.onSample(sample(2000, fps = 40f)))
    }

    @Test fun `about to throttle steps down at once`() {
        val g = QualityGovernor(QualityTier.ULTRA)
        assertEquals(QualityTier.HIGH, g.onSample(sample(0, headroom = 0.97f)))
    }

    @Test fun `step downs are spaced by the cooldown`() {
        val g = QualityGovernor(QualityTier.ULTRA)
        g.onSample(sample(0, headroom = 0.99f))
        assertEquals(QualityTier.HIGH, g.onSample(sample(1000, headroom = 0.99f)))
        assertEquals(QualityTier.LOW, g.onSample(sample(5000, headroom = 0.99f)))
        assertEquals(QualityTier.LOW, g.onSample(sample(10000, headroom = 0.99f))) // nothing lower
    }

    @Test fun `easy frames step up slowly`() {
        val g = QualityGovernor(QualityTier.LOW)
        var t = 0L
        repeat(4) { assertEquals(QualityTier.LOW, g.onSample(sample(t))); t += 2000 }
        assertEquals(QualityTier.HIGH, g.onSample(sample(t)))
    }

    @Test fun `no step up soon after a step down`() {
        val g = QualityGovernor(QualityTier.HIGH)
        g.onSample(sample(0, headroom = 0.99f))
        assertEquals(QualityTier.LOW, g.tier)
        var t = 2000L
        repeat(8) { g.onSample(sample(t)); t += 2000 } // 18 s of easy frames
        assertEquals(QualityTier.LOW, g.tier)
        repeat(3) { g.onSample(sample(t)); t += 2000 }
        assertEquals(QualityTier.HIGH, g.tier)
    }

    @Test fun `a warm device never steps up`() {
        val g = QualityGovernor(QualityTier.LOW)
        repeat(20) { g.onSample(sample(it * 2000L, headroom = 0.8f)) }
        assertEquals(QualityTier.LOW, g.tier)
    }

    @Test fun `heavy work per frame is not easy even at full rate`() {
        val g = QualityGovernor(QualityTier.LOW)
        repeat(20) { g.onSample(sample(it * 2000L, work = 12f)) }
        assertEquals(QualityTier.LOW, g.tier)
    }

    @Test fun `unknown headroom counts as cool`() {
        val g = QualityGovernor(QualityTier.LOW)
        repeat(5) { g.onSample(sample(it * 2000L, headroom = null)) }
        assertEquals(QualityTier.HIGH, g.tier)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the governor does not start on the CI tier`() {
        QualityGovernor(QualityTier.CI)
    }

    @Test fun `a rolling mean forgets old values`() {
        val m = RollingMean(3)
        assertEquals(0f, m.mean, 0f)
        m.add(3f); m.add(6f)
        assertEquals(4.5f, m.mean, 1e-6f)
        m.add(9f); m.add(12f)
        assertEquals(9f, m.mean, 1e-6f)
        assertEquals(3, m.size)
    }
}
