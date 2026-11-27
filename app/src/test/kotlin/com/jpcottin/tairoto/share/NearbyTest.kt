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

    @Test fun `Wi-Fi Direct comes after the network and before Bluetooth`() {
        val bt = NearbyLagoon("Tablet", "AA:BB:CC:DD:EE:01", 0x80, NearbyLagoon.Via.BLUETOOTH)
        val direct = WifiDirectLagoons.Record("Tablet", 41234, "DIRECT-ab-Tairoto", "secretpass12").lagoon()
        val wifi = NearbyLagoon("Tablet", "192.168.1.20", 41234)
        assertEquals(listOf(direct), NearbyLagoon.oneEach(listOf(bt, direct)))
        assertEquals(listOf(wifi), NearbyLagoon.oneEach(listOf(bt, direct, wifi)))
    }

    @Test fun `a Wi-Fi Direct lagoon says its group and port, and is read back`() {
        val r = WifiDirectLagoons.Record("Moana's tablet", 41234, "DIRECT-ab-Tairoto", "k3yk3yk3yk3y")
        assertEquals(r, WifiDirectLagoons.Record.parse(r.txt()))
        val lagoon = r.lagoon()
        assertEquals(NearbyLagoon.Via.WIFI_DIRECT, lagoon.via)
        assertEquals(r, WifiDirectLagoons.Record.fromLagoon(lagoon))
        // Not a lagoon: no port, a group that is not Wi-Fi Direct's, a passphrase too short.
        assertNull(WifiDirectLagoons.Record.parse(r.txt() - "p"))
        assertNull(WifiDirectLagoons.Record.parse(r.txt() + ("g" to "HomeWifi")))
        assertNull(WifiDirectLagoons.Record.parse(r.txt() + ("k" to "short")))
        assertNull(WifiDirectLagoons.Record.fromLagoon(NearbyLagoon("Tablet", "192.168.1.20", 41234)))
        // A record without a name still joins.
        assertEquals("A lagoon", WifiDirectLagoons.Record.parse(r.txt() - "n")!!.name)
    }

    @Test fun `a group's name and passphrase are what Wi-Fi Direct takes, and differ each time`() {
        val random = java.util.Random(4)
        val a = WifiDirectLagoons.Group.make(random)
        val b = WifiDirectLagoons.Group.make(random)
        assertTrue(a.networkName.matches(Regex("DIRECT-[A-Za-z0-9]{2}-Tairoto")))
        assertTrue(a.passphrase.length in 8..63)
        assertTrue(a != b)
    }
}
