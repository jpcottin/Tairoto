package com.jpcottin.tairoto

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.device.DeviceInteraction.Companion.setClosedMode
import androidx.test.espresso.device.DeviceInteraction.Companion.setFlatMode
import androidx.test.espresso.device.EspressoDevice.Companion.onDevice
import androidx.test.espresso.device.rules.DisplaySizeRule
import androidx.test.espresso.device.filter.RequiresDeviceMode
import androidx.test.espresso.device.controller.DeviceMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jpcottin.tairoto.ui.navigation.BottomSheetSceneStrategy
import com.jpcottin.tairoto.ui.settings.SettingsTags
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.world.SpeciesCatalog
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Folding and unfolding a real (emulated) foldable while the app runs. Skipped
 * on a device that cannot fold. The lagoon must carry on through every fold:
 * the same creatures, an open picker moving between sheet and pane, and a
 * lagoon that widens for the inner screen and keeps that size when folded.
 */
@RunWith(AndroidJUnit4::class)
class FoldTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val displaySize = DisplaySizeRule()

    private val creatures = SpeciesCatalog.all.sumOf { it.defaultCount }

    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun shell(command: String) =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()

    /**
     * A fold puts this emulator to sleep behind "swipe up to continue" a few
     * seconds later (the system's choice, not the app's; the setting to stay
     * awake is ignored by the image). Wake it and carry on as a user would,
     * until the lagoon is in front and stays there.
     */
    private fun wake() {
        fun front() = runCatching { present(TankTags.SURFACE) }.getOrDefault(false)
        repeat(8) {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            Thread.sleep(1_000)
            if (front()) {
                Thread.sleep(2_000)  // and still there once the system has settled
                if (front()) return
            }
        }
        compose.waitUntil(5_000) { front() }
    }

    private fun fold() { onDevice().setClosedMode(); Thread.sleep(3_000); wake() }
    private fun unfold() { onDevice().setFlatMode(); wake() }

    /** The emulator's controller is on the host, a local address: allowed with this permission (Android 17). */
    @Before fun allowLocalNetworkAndWake() {
        shell("pm grant com.jpcottin.tairoto android.permission.ACCESS_LOCAL_NETWORK")
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After fun openFlat() = runCatching { onDevice().setFlatMode() }.let { }

    @RequiresDeviceMode(DeviceMode.FLAT)
    @RequiresDeviceMode(DeviceMode.CLOSED)
    @Test fun anOpenPickerFollowsTheFoldBetweenSheetAndPane() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            fold()  // a device can only be folded with an activity in front
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(BottomSheetSceneStrategy.TAG)

            unfold()
            compose.waitForTag(SheetTags.PANE)
            assertTrue("no sheet once unfolded", !present(BottomSheetSceneStrategy.TAG))
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }

            fold()
            compose.waitForTag(BottomSheetSceneStrategy.TAG)
            assertTrue("no pane once folded", !present(SheetTags.PANE))
        }
    }

    @RequiresDeviceMode(DeviceMode.FLAT)
    @RequiresDeviceMode(DeviceMode.CLOSED)
    @Test fun unfoldingWidensTheLagoonAndFoldingKeepsIt() {
        // Fold (it takes an activity in front), then start afresh on the cover
        // screen: the lagoon is made for the small screen.
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            fold()
        }
        AppLauncher.launch().use {
            wake()  // the fold may have put the device to sleep
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            fun lagoonMm(): String {
                compose.onNodeWithTag(TankTags.SETTINGS).performClick()
                compose.waitForTag(SettingsTags.LAGOON)
                val text = compose.onNodeWithTag(SettingsTags.LAGOON).text()
                compose.onNodeWithTag(SettingsTags.BACK).performClick()
                compose.waitUntil(10_000) { !present(SettingsTags.SCREEN) }
                return text
            }
            val folded = lagoonMm()
            unfold()
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            val open = lagoonMm()
            assertTrue("$folded -> $open", open != folded)
            fold()
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            assertTrue("kept when folded again", lagoonMm() == open)
        }
    }
}
