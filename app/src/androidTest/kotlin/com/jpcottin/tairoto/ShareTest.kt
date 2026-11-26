package com.jpcottin.tairoto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.net.RelayLink
import com.jpcottin.tairoto.net.RelayServer
import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.NearbyLagoon
import com.jpcottin.tairoto.share.RelayAddress
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.ui.share.ShareActions
import com.jpcottin.tairoto.ui.share.SharePillTag
import com.jpcottin.tairoto.ui.share.ShareSheet
import com.jpcottin.tairoto.ui.share.ShareTags
import com.jpcottin.tairoto.ui.tank.ShareDeviceUi
import com.jpcottin.tairoto.ui.tank.SharePhase
import com.jpcottin.tairoto.ui.tank.ShareRole
import com.jpcottin.tairoto.ui.tank.ShareUi
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.world.ExactlyOnce
import com.jpcottin.tairoto.world.FakeSim
import com.jpcottin.tairoto.world.RectMm
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.SharedTank
import com.jpcottin.tairoto.world.TankSession
import com.jpcottin.tairoto.world.TankSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The share sheet on its own. */
@RunWith(AndroidJUnit4::class)
class ShareSheetTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : ShareActions {
        val shared = mutableListOf<ShareRoute>()
        val joined = mutableListOf<JoinTarget>()
        var stopped = 0
        var relay = ""
        override fun share(route: ShareRoute) { shared += route }
        override fun join(target: JoinTarget) { joined += target }
        override fun stop() { stopped++ }
        override fun relayChanged(text: String) { relay = text }
        override fun dismissMessage() = Unit
    }

    private val tablet = NearbyLagoon("Tablet", "192.168.1.20", 41234)

    @Test fun nothingSharedYetOffersToShareAndListsTheLagoonsNearby() {
        val r = Recorder()
        compose.setContent { TairotoTheme { ShareSheet(ShareUi(nearby = listOf(tablet)), r, onClose = null) } }
        compose.onNodeWithText("Share the lagoon").assertIsDisplayed()
        compose.onNodeWithTag(ShareTags.SHARE_NEARBY).performClick()
        assertEquals(listOf<ShareRoute>(ShareRoute.Nearby), r.shared)
        compose.onNodeWithText("Tablet's lagoon").assertIsDisplayed()
        compose.onNodeWithText("Join").performClick()
        assertEquals(listOf<JoinTarget>(JoinTarget.Nearby(tablet)), r.joined)
    }

    @Test fun throughAComputerTheRelayCanBeTypedAndAWrongAddressCannotBeUsed() {
        val r = Recorder()
        var relay by androidx.compose.runtime.mutableStateOf("10.0.2.2:7171")
        compose.setContent {
            TairotoTheme {
                ShareSheet(ShareUi(relay = relay), object : ShareActions by r {
                    override fun relayChanged(text: String) { relay = text }
                }, onClose = null)
            }
        }
        compose.onNodeWithTag(ShareTags.COMPUTER).performScrollTo().performClick()
        compose.onNodeWithTag(ShareTags.SHARE_RELAY).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<ShareRoute>(ShareRoute.Relay(RelayAddress("10.0.2.2", 7171))), r.shared)
        compose.onNodeWithTag(ShareTags.RELAY_FIELD).performTextReplacement("not an address:x")
        compose.onNodeWithTag(ShareTags.SHARE_RELAY).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.JOIN_RELAY).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.RELAY_FIELD).performTextReplacement("192.168.1.5:9000")
        compose.onNodeWithTag(ShareTags.JOIN_RELAY).performClick()
        assertEquals(listOf<JoinTarget>(JoinTarget.Relay(RelayAddress("192.168.1.5", 9000))), r.joined)
    }

    @Test fun sharedTheTableIsShownAndTheGuestCanLeave() {
        val r = Recorder()
        val devices = listOf(
            ShareDeviceUi("Tablet", RectMm(20f, 30f, 250f, 160f), self = false, host = true),
            ShareDeviceUi("Pixel 9", RectMm(282f, 30f, 68f, 150f), self = true, host = false),
        )
        compose.setContent {
            TairotoTheme {
                ShareSheet(
                    ShareUi(SharePhase.SHARED, ShareRole.GUEST, devices, TankSize(380f, 220f, 55f), here = 7, arrived = 3, left = 1),
                    r, onClose = null,
                )
            }
        }
        compose.onNodeWithText("In Tablet's lagoon").assertIsDisplayed()
        compose.onNodeWithTag(ShareTags.MAP).assertIsDisplayed()
        compose.onNodeWithTag(ShareTags.STATUS).assertIsDisplayed()
        compose.onNodeWithText("Leave the lagoon").performClick()
        assertEquals(1, r.stopped)
    }

    @Test fun outOfTouchTheGuestIsTold() {
        compose.setContent {
            TairotoTheme {
                ShareSheet(
                    ShareUi(SharePhase.SHARED, ShareRole.GUEST, listOf(ShareDeviceUi("Tablet", RectMm(0f, 0f, 250f, 160f), false, true)), TankSize(300f, 200f, 55f), connected = false),
                    Recorder(), onClose = null,
                )
            }
        }
        compose.onNodeWithText("Out of touch with Tablet. The fish here wait until it answers.").assertIsDisplayed()
    }
}

/**
 * The real app, its native lagoon, shared through a relay running in this
 * process; a guest made of the JVM fakes joins it over the loopback, and
 * fish from the app's lagoon swim over onto the guest's screen.
 */
@RunWith(AndroidJUnit4::class)
class SharedAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aGuestJoinsTheAppsLagoonAndFishSwimOverToIt() {
        RelayServer(0).use { relay ->
            val scenario = AppLauncher.launch(ci = false) {
                putExtra(LaunchOptions.SHARE, "host")
                putExtra(LaunchOptions.RELAY, "127.0.0.1:${relay.port}")
                putExtra(LaunchOptions.SESSION, "instrumented")
            }
            compose.waitForText(SharePillTag) { it.contains("1 screen") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            try {
                runBlocking {
                    val link = RelayLink.connect("127.0.0.1", relay.port, "instrumented", "Test guest", asHost = false)
                    val join = SharedTank.requestJoin(link, ScreenMm(68f, 150f), 10_000)
                    assertNotNull("the app answered", join)
                    join!!
                    assertTrue("the app's whole lagoon came", join.scene.creatures.size > 40)
                    assertEquals(2, join.layout.placements.size)
                    val monitor = ExactlyOnce()
                    val sim = FakeSim("guest", link.self.slot, join.layout.tank, monitor, 3)
                    val start = System.nanoTime()
                    withContext(scope.coroutineContext) {
                        val session = TankSession(join.scene, link)
                        val shared = SharedTank(session, link, sim, { (System.nanoTime() - start) / 1e9 }, join.layout, join.owners)
                        scope.launch { shared.run() }
                        scope.launch {
                            while (isActive) {
                                sim.sync(session.scene.value)
                                sim.advance(1f / 60f)
                                delay(16)
                            }
                        }
                    }
                    compose.waitForText(SharePillTag) { it.contains("2 screens") }
                    // Fish swim off the app's screen onto the guest's.
                    val until = System.currentTimeMillis() + 60_000
                    while (withContext(scope.coroutineContext) { sim.ownedCount() } == 0 && System.currentTimeMillis() < until) delay(250)
                    assertTrue("no fish came over", withContext(scope.coroutineContext) { sim.ownedCount() } > 0)
                    link.close()
                }
            } finally {
                scope.cancel()
                scenario.close()
            }
        }
    }
}
