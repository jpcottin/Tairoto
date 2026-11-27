package com.jpcottin.tairoto.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyTest {
    @Test fun `the channel number goes in two bytes and comes back`() {
        for (psm in listOf(0x80, 0xC5, 0x1001, 0xFFFF)) assertEquals(psm, Advert.decodePsm(Advert.encodePsm(psm)))
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()), Advert.encodePsm(128))
        assertNull(Advert.decodePsm(null))
        assertNull(Advert.decodePsm(byteArrayOf(1)))
        assertNull(Advert.decodePsm(byteArrayOf(0, 0)))
    }

    @Test fun `a name is cut to fit the advertisement, never inside a character`() {
        assertEquals("Pixel 9", Advert.decodeName(Advert.encodeName("Pixel 9")))
        val long = Advert.encodeName("The living room tablet by the window")
        assertTrue(long.size <= Advert.MAX_NAME_BYTES)
        assertEquals("The living room tablet by ", Advert.decodeName(long))
        // Tahitian: the ʻeta takes two bytes, and is kept whole or not at all.
        val tahitian = Advert.encodeName("Te tamaʻiti ʻōmaʻomaʻo nō Moʻorea")
        assertTrue(tahitian.size <= Advert.MAX_NAME_BYTES)
        val back = Advert.decodeName(tahitian)!!
        assertTrue("Te tamaʻiti ʻōmaʻomaʻo nō Moʻorea".startsWith(back))
        assertTrue(back.none { it == '�' })
        assertNull(Advert.decodeName(ByteArray(0)))
        assertNull(Advert.decodeName(null))
    }

    @Test fun `a lagoon heard of both ways is listed once, on Wi-Fi`() {
        val tabletWifi = NearbyLagoon("Tablet", "192.168.1.20", 41234)
        val tabletBt = NearbyLagoon("Tablet", "AA:BB:CC:DD:EE:01", 0x80, NearbyLagoon.Via.BLUETOOTH)
        val phoneBt = NearbyLagoon("Phone", "AA:BB:CC:DD:EE:02", 0x81, NearbyLagoon.Via.BLUETOOTH)
        assertEquals(listOf(tabletWifi, phoneBt), NearbyLagoon.oneEach(listOf(tabletBt, phoneBt, tabletWifi)))
        assertEquals(listOf(phoneBt), NearbyLagoon.oneEach(listOf(phoneBt)))
    }
}
