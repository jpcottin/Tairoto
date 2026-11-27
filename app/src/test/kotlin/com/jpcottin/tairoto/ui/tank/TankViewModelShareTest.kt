package com.jpcottin.tairoto.ui.tank

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jpcottin.tairoto.LaunchOptions
import com.jpcottin.tairoto.ShareLaunch
import com.jpcottin.tairoto.data.InMemoryLagoonStore
import com.jpcottin.tairoto.data.InMemorySettings
import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.NearbyLagoon
import com.jpcottin.tairoto.share.RelayAddress
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.world.DecorSpec
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.FakeNetwork
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Two devices of a shared lagoon, each a view model with its own engine, on one fake network. */
@OptIn(ExperimentalCoroutinesApi::class)
class TankViewModelShareTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = ArrayList<ViewModelStore>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** The view models' loops run until they are cleared: cleared before the test ends, so that it can. */
    private fun shareTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher, timeout = 30.seconds) {
        try {
            body()
        } finally {
            stores.forEach { it.clear() }
            stores.clear()
        }
    }

    private class Device(val vm: TankViewModel, val engine: FakeEngine, val store: InMemoryLagoonStore, val log: MutableList<String>)

    private fun TestScope.device(
        connector: FakeShareConnector,
        name: String,
        lagoonSeed: Int,
        options: LaunchOptions = LaunchOptions(seed = 7, hints = false),
        decor: List<DecorSpec> = emptyList(),
    ): Device {
        val engine = FakeEngine()
        val size = TankSize(150f, 330f, 55f)
        val scene = Population.sceneOf(mapOf("green_chromis" to 3 + lagoonSeed), size, EntityIdAllocator(0), Random(lagoonSeed))
        val store = InMemoryLagoonStore(scene.copy(decor = decor))
        val log = mutableListOf<String>()
        val vmStore = ViewModelStore().also { stores += it }
        val factory = viewModelFactory {
            initializer {
                TankViewModel(
                    engine, InMemorySettings(), options, FakeSignals(), dispatcher, store,
                    connector = connector, deviceName = name, netLog = { log += it },
                    clockS = { testScheduler.currentTime / 1000.0 },
                )
            }
        }
        val vm = ViewModelProvider(vmStore, factory)[TankViewModel::class.java]
        runCurrent()
        vm.onSurfaceSize(1080, 2400, 420f, 420f)
        runCurrent()
        return Device(vm, engine, store, log)
    }

    private fun TestScope.network() = FakeNetwork(backgroundScope, { testScheduler.currentTime }, latencyMs = 10, jitterMs = 5)

    @Test fun `sharing keeps the lagoon and every creature in it`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        val before = host.vm.ui.value.total
        val spawned = host.engine.creatures.keys.toSet()
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val share = host.vm.ui.value.share
        assertEquals(SharePhase.SHARED, share.phase)
        assertEquals(ShareRole.HOST, share.role)
        assertEquals(listOf("Tablet"), share.devices.map { it.name })
        assertEquals(before, host.vm.ui.value.total)
        // Nothing was spawned again, nothing became a ghost: the host simulates its whole lagoon.
        assertEquals(spawned, host.engine.creatures.keys)
        assertTrue(host.engine.ghosts.isEmpty())
        assertEquals(before, share.here)
        assertEquals(listOf<ShareRoute>(ShareRoute.Nearby), connector.routes)
    }

    @Test fun `shared, each screen looks life size at its place on the table, and does not pan or zoom`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val rect = host.vm.ui.value.share.devices.single().rect
        val cam = host.vm.camera!!
        assertEquals(rect.center, cam.center)
        assertEquals(1f, cam.zoom, 0.01f)
        assertNotNull(cam.parallaxOrigin)
        host.vm.pan(Vec2(300f, 0f))
        host.vm.zoom(Vec2(500f, 500f), 2f)
        assertEquals(cam, host.vm.camera)
    }

    @Test fun `a guest joins the host's lagoon, its creatures arrive as ghosts, its own come along, and the table has both screens`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        val hosts = host.engine.creatures.keys.toSet()
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        val own = guest.vm.ui.value.total
        guest.vm.joinLagoon(JoinTarget.Nearby(NearbyLagoon("Tablet", "10.0.0.2", 7171)))
        advanceTimeBy(1_000)
        val g = guest.vm.ui.value.share
        assertEquals(SharePhase.SHARED, g.phase)
        assertEquals(ShareRole.GUEST, g.role)
        assertEquals("Tablet", g.hostName)
        assertEquals(listOf("Tablet", "Phone"), g.devices.map { it.name })
        assertEquals(listOf("Tablet", "Phone"), host.vm.ui.value.share.devices.map { it.name })
        assertEquals(host.vm.ui.value.total, guest.vm.ui.value.total)
        assertEquals(hosts.size + own, guest.vm.ui.value.total)
        assertEquals(host.engine.creatures.keys, guest.engine.creatures.keys)
        // The host's swim here as ghosts; the guest's own, come along, swim here for real.
        assertEquals(hosts, guest.engine.ghosts)
        assertEquals(own, guest.vm.ui.value.share.here)
        assertEquals(1, guest.engine.slotOfFood)
        // The tank is the table's, the same on both.
        assertEquals(host.vm.ui.value.tankSize, guest.vm.ui.value.tankSize)
    }

    @Test fun `a guest shows the host's floor and fish, even where its own lagoon used the same ids`() = shareTest {
        val connector = FakeShareConnector(network())
        // Two lagoons made apart both count from 1: the same ids, different things.
        val coral = DecorSpec(EntityId(100), "coral_head", 3, Vec2(60f, 120f), 1f, 0.8f)
        val stone = DecorSpec(EntityId(100), "stone", 9, Vec2(20f, 40f), 0f, 0.3f)
        val host = device(connector, "Tablet", 1, decor = listOf(coral))
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2, decor = listOf(stone, stone.copy(id = EntityId(101))))
        assertEquals(stone, guest.engine.decor[EntityId(100)])
        val shared = host.engine.creatures.keys intersect guest.engine.creatures.keys
        assertTrue("the lagoons should share ids", shared.isNotEmpty())
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        assertEquals(SharePhase.SHARED, guest.vm.ui.value.share.phase)
        // The host's coral, and the guest's two stones come along under new ids.
        assertEquals(coral, guest.engine.decor[coral.id])
        val stones = guest.engine.decor.values.filter { it.kindId == "stone" }
        assertEquals(2, stones.size)
        assertTrue(stones.all { it.home == 1 && it.id != EntityId(100) && it.id != EntityId(101) })
        assertEquals(guest.engine.decor, host.engine.decor)
        // Every fish is the host's own (its species, its pattern, its size), not the guest's under the same id.
        for ((id, spec) in host.engine.creatures) {
            val g = guest.engine.creatures[id]!!
            assertEquals(spec.speciesId to spec.seed, g.speciesId to g.seed)
            assertEquals(spec.lengthMm, g.lengthMm, 0f)
        }
        // And home again, the guest's own floor is back as it was.
        guest.vm.stopSharing()
        advanceTimeBy(1_000)
        assertEquals(setOf(EntityId(100), EntityId(101)), guest.engine.decor.keys)
        assertEquals(stone, guest.engine.decor[EntityId(100)])
    }

    @Test fun `a guest's own lagoon is kept as it was and comes back when it leaves`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        val own = guest.vm.ui.value.total
        val saved = guest.store.scene!!.creatures.map { it.id }
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(5_000)
        // Nothing of the host's lagoon was saved over the guest's.
        assertEquals(saved, guest.store.scene!!.creatures.map { it.id })
        guest.vm.saveNow()
        advanceTimeBy(100)
        assertEquals(saved, guest.store.scene!!.creatures.map { it.id })
        guest.vm.stopSharing()
        advanceTimeBy(1_000)
        assertEquals(SharePhase.OFF, guest.vm.ui.value.share.phase)
        assertEquals(own, guest.vm.ui.value.total)
        assertEquals(saved.toSet(), guest.engine.creatures.keys)
        assertTrue(guest.engine.ghosts.isEmpty())
        // The host sees it go.
        advanceTimeBy(500)
        assertEquals(1, host.vm.ui.value.share.devices.size)
    }

    @Test fun `when the host stops sharing the guest goes home and is told`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        val own = guest.vm.ui.value.total
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        host.vm.stopSharing()
        advanceTimeBy(2_000)
        assertEquals(SharePhase.OFF, host.vm.ui.value.share.phase)
        assertNull(host.vm.ui.value.share.message)
        assertEquals(SharePhase.OFF, guest.vm.ui.value.share.phase)
        assertEquals("Tablet stopped sharing", guest.vm.ui.value.share.message)
        assertEquals(own, guest.vm.ui.value.total)
        // The host keeps everything, simulated by itself again.
        assertTrue(host.engine.ghosts.isEmpty())
    }

    @Test fun `a tap reaches the fish on the next screen`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        guest.vm.tap(Vec2(540f, 1200f))
        advanceTimeBy(500)
        assertEquals(1, host.engine.remoteTaps.size)
        assertEquals(guest.engine.taps.single(), host.engine.remoteTaps.single())
    }

    @Test fun `a lagoon that cannot be reached says so and nothing changes`() = shareTest {
        val connector = FakeShareConnector(network())
        connector.failure = IOException("Connection refused")
        val d = device(connector, "Phone", 2)
        val total = d.vm.ui.value.total
        d.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(500)
        assertEquals(SharePhase.OFF, d.vm.ui.value.share.phase)
        assertEquals("Could not join the lagoon: Connection refused", d.vm.ui.value.share.message)
        assertEquals(total, d.vm.ui.value.total)
        d.vm.dismissShareMessage()
        assertNull(d.vm.ui.value.share.message)
    }

    @Test fun `lagoons nearby are looked for while asked, and listed`() = shareTest {
        val connector = FakeShareConnector(network())
        val d = device(connector, "Phone", 2)
        d.vm.lookForLagoons(true)
        assertTrue(connector.discovering)
        connector.announce(NearbyLagoon("Tablet", "192.168.1.20", 41234))
        runCurrent()
        assertEquals(listOf("Tablet"), d.vm.ui.value.share.nearby.map { it.name })
        d.vm.lookForLagoons(false)
        assertTrue(!connector.discovering)
    }

    @Test fun `launched to share or to join, the devices find each other on their own`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1, LaunchOptions(seed = 7, hints = false, share = ShareLaunch(true, "10.0.2.2:7171", "ci")))
        val guest = device(connector, "Phone", 2, LaunchOptions(seed = 8, hints = false, share = ShareLaunch(false, null, "ci")))
        advanceTimeBy(4_000)
        assertEquals(SharePhase.SHARED, host.vm.ui.value.share.phase)
        assertEquals(SharePhase.SHARED, guest.vm.ui.value.share.phase)
        assertEquals(ShareRoute.Relay(RelayAddress("10.0.2.2", 7171), "ci"), connector.routes.single())
        assertEquals(JoinTarget.Relay(RelayAddress.EMULATOR_HOST, "ci"), connector.targets.last())
        // Every step is logged, one JSON line each.
        assertTrue(host.log.any { it.startsWith("{\"ev\":\"share\"") && it.contains("\"role\":\"host\"") })
        advanceTimeBy(3_000)
        assertTrue(guest.log.any { it.startsWith("{\"ev\":\"status\"") })
    }

    @Test fun `launched to join over Bluetooth, the guest takes the lagoon heard of over Bluetooth`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1, LaunchOptions(seed = 7, hints = false, share = ShareLaunch(true, "bluetooth", "ci")))
        advanceTimeBy(500)
        assertEquals(ShareRoute.Nearby, connector.routes.single())
        val wifi = NearbyLagoon("Tablet", "192.168.1.20", 41234)
        val bt = NearbyLagoon("Tablet", "AA:BB:CC:DD:EE:01", 0x80, NearbyLagoon.Via.BLUETOOTH)
        val guest = device(connector, "Phone", 2, LaunchOptions(seed = 8, hints = false, share = ShareLaunch(false, "bluetooth", "ci")))
        assertTrue(connector.discovering)
        // Nothing heard yet: it waits rather than going anywhere.
        advanceTimeBy(3_000)
        assertTrue(connector.targets.isEmpty())
        connector.announce(wifi, bt)
        advanceTimeBy(4_000)
        assertEquals(JoinTarget.Nearby(bt), connector.targets.single())
        assertEquals(SharePhase.SHARED, guest.vm.ui.value.share.phase)
        assertEquals(SharePhase.SHARED, host.vm.ui.value.share.phase)
    }

    @Test fun `a guest that goes to the background leaves, handing its fish to the host`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        guest.vm.onBackground()
        advanceTimeBy(1_000)
        assertEquals(SharePhase.OFF, guest.vm.ui.value.share.phase)
        assertEquals(1, host.vm.ui.value.share.devices.size)
        assertTrue(host.engine.ghosts.isEmpty())
    }

    @Test fun `addresses of a relay are read forgivingly`() {
        assertEquals(RelayAddress("10.0.2.2", 7171), RelayAddress.parse("10.0.2.2"))
        assertEquals(RelayAddress("relay.local", 9000), RelayAddress.parse(" relay.local:9000 "))
        assertNull(RelayAddress.parse("host:99999"))
        assertNull(RelayAddress.parse("host:abc"))
        assertNull(RelayAddress.parse(""))
        assertNull(RelayAddress.parse("a b:12"))
    }

    // ------------------------------------------------------------ the table

    private suspend fun TestScope.pair(connector: FakeShareConnector): Pair<Device, Device> {
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        val guest = device(connector, "Phone", 2)
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        return host to guest
    }

    @Test fun `the guest turns its screen on the map, and its camera draws the lagoon turned back`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        val me = guest.vm.ui.value.share.devices.single { it.self }
        guest.vm.arrangeScreen(me.id, me.rect.center + Vec2(0f, 200f), 1)
        advanceTimeBy(500)
        val after = host.vm.ui.value.share.devices.single { it.name == "Phone" }
        assertEquals(1, after.turns)
        assertEquals(1, guest.engine.camera!!.turns)
        assertEquals(0, host.engine.camera!!.turns)
        // Life size across: the glass's width (1080 px at 420 dpi) covers the screen's width in mm.
        val vp = guest.engine.viewport!!
        assertEquals(1080f / 420f * 25.4f, vp.widthPx / guest.engine.camera!!.pxPerMm(vp), 0.01f)
        // The camera looks at the middle of the screen as it lies now.
        assertEquals(after.rect.center, guest.engine.camera!!.center)
    }

    @Test fun `arranging shows on every screen, and a finger there feeds nobody`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        guest.vm.setArranging(true)
        advanceTimeBy(300)
        assertTrue(host.vm.ui.value.share.arranging && guest.vm.ui.value.share.arranging)
        val drops = host.engine.drops.size
        host.vm.sprinkle(Vec2(500f, 500f), first = true)
        host.vm.sprinkle(Vec2(600f, 700f), first = false)
        host.vm.dropPellet(Vec2(500f, 500f))
        assertEquals(drops, host.engine.drops.size)
        host.vm.setArranging(false)
        advanceTimeBy(300)
        assertTrue(!host.vm.ui.value.share.arranging && !guest.vm.ui.value.share.arranging)
    }

    @Test fun `a finger swiped off the guest's right edge onto the host's left lays the host right of the guest`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        host.vm.setArranging(true)
        advanceTimeBy(300)
        // Off the guest's right edge, a third of the way down; onto the host's left edge, half way down.
        guest.vm.strokeEnd(Vec2(900f, 800f), Vec2(1079f, 800f))
        advanceTimeBy(80)
        host.vm.strokeStart(Vec2(1f, 1200f), Vec2(80f, 1200f))
        advanceTimeBy(500)
        val devices = guest.vm.ui.value.share.devices
        val g = devices.single { it.name == "Phone" }.rect
        val h = devices.single { it.name == "Tablet" }.rect
        assertEquals(2 * com.jpcottin.tairoto.world.ScreenMm.PHONE_BEZEL_MM, h.x - g.right, 0.05f)
        // Where the finger left is level with where it came on.
        val mm = 25.4f / 420f
        assertEquals(g.y + 800f * mm, h.y + 1200f * mm, 0.05f)
        assertTrue(host.log.any { "\"stitched\"" in it })
    }

    @Test fun `away from arranging, a finger at an edge is only a finger`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        val before = host.vm.ui.value.share.devices
        guest.vm.strokeEnd(Vec2(900f, 800f), Vec2(1079f, 800f))
        host.vm.strokeStart(Vec2(1f, 1200f), Vec2(80f, 1200f))
        advanceTimeBy(500)
        assertEquals(before, host.vm.ui.value.share.devices)
    }

    @Test fun `what the guest brought goes home with it, and the host never saves it`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        val hostOwn = host.store.scene!!.creatures.size
        assertTrue(host.vm.ui.value.total > hostOwn)
        advanceTimeBy(3_000)
        assertTrue("saved without visitors", host.store.scene!!.creatures.none { it.home != null })
        guest.vm.stopSharing()
        advanceTimeBy(4_000)
        assertEquals(hostOwn, host.vm.ui.value.total)
        assertTrue(host.engine.creatures.values.none { it.home != null })
        // The guest is home with its own lagoon, as it was.
        assertEquals(guest.store.scene!!.creatures.size, guest.vm.ui.value.total)
    }

    @Test fun `when the host stops, what guests brought is not kept in its lagoon`() = shareTest {
        val (host, _) = pair(FakeShareConnector(network()))
        val hostOwn = host.store.scene!!.creatures.size
        host.vm.stopSharing()
        advanceTimeBy(2_000)
        assertEquals(hostOwn, host.vm.ui.value.total)
        assertTrue(host.engine.creatures.values.none { it.home != null })
    }

    @Test fun `a second screen coming in offers, for a moment, to line them up`() = shareTest {
        val connector = FakeShareConnector(network())
        val host = device(connector, "Tablet", 1)
        host.vm.shareLagoon(ShareRoute.Nearby)
        advanceTimeBy(500)
        // Alone, nothing to line up.
        assertTrue(!host.vm.ui.value.share.arrangeHint)
        val guest = device(connector, "Phone", 2)
        guest.vm.joinLagoon(JoinTarget.Relay(RelayAddress.EMULATOR_HOST))
        advanceTimeBy(1_000)
        assertTrue(host.vm.ui.value.share.arrangeHint && guest.vm.ui.value.share.arrangeHint)
        advanceTimeBy(TankViewModel.ARRANGE_HINT_MS)
        assertTrue(!host.vm.ui.value.share.arrangeHint && !guest.vm.ui.value.share.arrangeHint)
    }

    @Test fun `taking up the offer starts arranging on every screen, and the offer goes`() = shareTest {
        val (host, guest) = pair(FakeShareConnector(network()))
        assertTrue(guest.vm.ui.value.share.arrangeHint)
        guest.vm.dismissArrangeHint()
        guest.vm.setArranging(true)
        advanceTimeBy(300)
        assertTrue(host.vm.ui.value.share.arranging && guest.vm.ui.value.share.arranging)
        assertTrue(!host.vm.ui.value.share.arrangeHint && !guest.vm.ui.value.share.arrangeHint)
    }

    @Test fun `a shared screen left alone fades its buttons, and a touch brings them back`() = shareTest {
        val (host, _) = pair(FakeShareConnector(network()))
        host.vm.dismissArrangeHint()
        host.vm.touched()
        advanceTimeBy(3_000)
        assertTrue(!host.vm.ui.value.hudHidden)
        advanceTimeBy(5_000)
        assertTrue(host.vm.ui.value.hudHidden)
        host.vm.touched()
        assertTrue(!host.vm.ui.value.hudHidden)
        // Back on its own, the buttons stay.
        advanceTimeBy(8_000)
        assertTrue(host.vm.ui.value.hudHidden)
        host.vm.stopSharing()
        advanceTimeBy(2_000)
        assertTrue(!host.vm.ui.value.hudHidden)
        advanceTimeBy(10_000)
        assertTrue(!host.vm.ui.value.hudHidden)
    }

    @Test fun `while arranging, or offering to, the buttons stay`() = shareTest {
        val (host, _) = pair(FakeShareConnector(network()))
        advanceTimeBy(8_000) // the offer is up for ten seconds
        assertTrue(!host.vm.ui.value.hudHidden)
        host.vm.setArranging(true)
        advanceTimeBy(20_000)
        assertTrue(!host.vm.ui.value.hudHidden)
    }

    @Test fun `a host launched before its relay is up keeps trying, then shares`() = shareTest {
        val connector = FakeShareConnector(network())
        connector.failure = IOException("connection refused")
        val host = device(connector, "Tablet", 1, LaunchOptions(seed = 7, hints = false, share = ShareLaunch(true, "10.0.2.2:7171", "ci")))
        advanceTimeBy(1_500)
        assertEquals(SharePhase.OFF, host.vm.ui.value.share.phase)
        connector.failure = null
        advanceTimeBy(3_000)
        assertEquals(SharePhase.SHARED, host.vm.ui.value.share.phase)
    }
}
