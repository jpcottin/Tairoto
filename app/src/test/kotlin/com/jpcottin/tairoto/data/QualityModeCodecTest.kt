package com.jpcottin.tairoto.data

import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityModeCodecTest {
    @Test fun `every mode survives a round trip`() {
        val modes = listOf(QualityMode.Auto) + QualityTier.ladder.map { QualityMode.Fixed(it) }
        modes.forEach { assertEquals(it, QualityModeCodec.decode(QualityModeCodec.encode(it))) }
    }

    @Test fun `unknown or missing values fall back to automatic`() {
        assertEquals(QualityMode.Auto, QualityModeCodec.decode(null))
        assertEquals(QualityMode.Auto, QualityModeCodec.decode("potato"))
        assertEquals(QualityMode.Auto, QualityModeCodec.decode("ci")) // CI is never a user setting
    }

    @Test fun `values are case insensitive`() {
        assertEquals(QualityMode.Fixed(QualityTier.ULTRA), QualityModeCodec.decode("ULTRA"))
    }
}
