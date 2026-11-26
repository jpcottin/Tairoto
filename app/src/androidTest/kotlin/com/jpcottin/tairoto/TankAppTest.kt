package com.jpcottin.tairoto

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.ui.settings.SettingsTags
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SpeciesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole app on a device: the Vulkan tank, its HUD, the creature sheet and settings. */
@RunWith(AndroidJUnit4::class)
class TankAppTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun count() = compose.onNodeWithTag(TankTags.COUNT).text()

    @Test fun theTankOpensWithTheDefaultReef() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.TITLE).assertIsDisplayed()
            compose.onNodeWithTag(TankTags.ADD).assertIsDisplayed()
            compose.onNodeWithTag(TankTags.SETTINGS).assertIsDisplayed()
        }
    }

    @Test fun theEngineDrawsFrames() {
        AppLauncher.launch(stats = true).use {
            compose.waitForTag(TankTags.STATS)
            compose.waitForText(TankTags.STATS, timeoutMs = 20_000) { text ->
                Regex("(\\d+) frames").find(text)?.groupValues?.get(1)?.toInt()?.let { it >= 30 } == true
            }
            val text = compose.onNodeWithTag(TankTags.STATS).text()
            assertTrue(text, text.contains("CI"))
            assertTrue(text, Regex("(\\d+)/23 drawn").containsMatchIn(text))
        }
    }

    @Test fun releasingChromisAddsASchool() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.release(SpeciesCatalog.GreenChromis.id))
            compose.onNodeWithTag(SheetTags.release(SpeciesCatalog.GreenChromis.id)).performClick()
            compose.waitForText(SheetTags.count(SpeciesCatalog.GreenChromis.id)) { it == "28 in the lagoon" }
            Espresso.pressBack()
            compose.waitForText(TankTags.COUNT) { it == "29 creatures" }
        }
    }

    @Test fun releasingATurtle() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.release(SpeciesCatalog.GreenSeaTurtle.id))
            compose.onNodeWithText("Release one").performClick()
            compose.waitForText(SheetTags.count(SpeciesCatalog.GreenSeaTurtle.id)) { it == "2 in the lagoon" }
        }
    }

    @Test fun settingsOpenAndGoBack() {
        AppLauncher.launch().use {
            compose.waitForTag(TankTags.SETTINGS)
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.SCREEN)
            compose.onNodeWithText("Performance overlay").assertIsDisplayed()
            compose.onNodeWithTag(SettingsTags.BACK).performClick()
            compose.waitForTag(TankTags.TITLE)
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
        }
    }

    @Test fun theSystemBackLeavesSettingsForTheTank() {
        AppLauncher.launch().use {
            compose.waitForTag(TankTags.SETTINGS)
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.SCREEN)
            Espresso.pressBack()
            compose.waitForTag(TankTags.TITLE)
        }
    }

    @Test fun thePerformanceOverlayIsSwitchedOnInSettings() {
        AppLauncher.launch().use {
            compose.waitForTag(TankTags.SETTINGS)
            assertTrue(compose.onAllNodes(hasTestTag(TankTags.STATS)).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.STATS)
            compose.onNodeWithTag(SettingsTags.STATS).performClick()
            compose.onNodeWithTag(SettingsTags.BACK).performClick()
            compose.waitForTag(TankTags.STATS)
        }
    }

    @Test fun aChosenQualityReachesTheEngine() {
        AppLauncher.launch(ci = false, stats = true).use {
            compose.waitForTag(TankTags.SETTINGS)
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.quality(QualityMode.Fixed(QualityTier.LOW)))
            compose.onNodeWithTag(SettingsTags.quality(QualityMode.Fixed(QualityTier.LOW))).performClick()
            compose.onNodeWithTag(SettingsTags.BACK).performClick()
            compose.waitForText(TankTags.STATS, timeoutMs = 15_000) { it.contains("Low") && it.contains("648×1440") || it.contains("Low ") }
        }
    }

    @Test fun restoringTheReefAfterChanges() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.release(SpeciesCatalog.GreenChromis.id))
            compose.onNodeWithTag(SheetTags.release(SpeciesCatalog.GreenChromis.id)).performClick()
            Espresso.pressBack()
            compose.waitForText(TankTags.COUNT) { it == "29 creatures" }
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.RESET)
            compose.onNodeWithTag(SettingsTags.RESET).performClick()
            compose.waitForTag(SettingsTags.RESET_CONFIRM)
            compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
            compose.onNodeWithTag(SettingsTags.BACK).performClick()
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
        }
    }

    @Test fun aDragSprinklesFoodAndTheFishEatIt() {
        AppLauncher.launch(stats = true).use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.GESTURES).performTouchInput {
                swipe(start = center - Offset(200f, 0f), end = center + Offset(200f, 100f), durationMillis = 800)
            }
            compose.waitForText(TankTags.STATS) { text ->
                Regex("food (\\d+)").find(text)?.groupValues?.get(1)?.toInt()?.let { it > 0 } == true
            }
            compose.waitForText(TankTags.STATS, timeoutMs = 60_000) { text ->
                Regex("eaten ([0-9.]+) mg").find(text)?.groupValues?.get(1)?.toFloat()?.let { it > 0f } == true
            }
        }
    }

    @Test fun aLongPressDropsAPellet() {
        AppLauncher.launch(stats = true).use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.GESTURES).performTouchInput { longClick(center, durationMillis = 900) }
            compose.waitForText(TankTags.STATS) { text ->
                Regex("food (\\d+)").find(text)?.groupValues?.get(1)?.toInt() == 1
            }
        }
    }

    @Test fun aDoubleTapZoomsIn() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.GESTURES).performTouchInput { doubleClick(center) }
            compose.waitForText(TankTags.ZOOM) { it == "2.0×" }
        }
    }

    @Test fun aPinchZooms() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.GESTURES).performTouchInput {
                pinch(
                    start0 = center - Offset(60f, 0f), end0 = center - Offset(260f, 0f),
                    start1 = center + Offset(60f, 0f), end1 = center + Offset(260f, 0f),
                    durationMillis = 400,
                )
            }
            compose.waitForTag(TankTags.ZOOM)
            val zoom = compose.onNodeWithTag(TankTags.ZOOM).text().removeSuffix("×").toFloat()
            assertTrue("zoom $zoom", zoom > 1.5f)
        }
    }

    @Test fun theTankSurvivesRotation() {
        AppLauncher.launch().use { scenario ->
            compose.waitForText(TankTags.COUNT) { it == "23 creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.release(SpeciesCatalog.GreenSeaTurtle.id))
            compose.onNodeWithTag(SheetTags.release(SpeciesCatalog.GreenSeaTurtle.id)).performClick()
            Espresso.pressBack()
            compose.waitForText(TankTags.COUNT) { it == "24 creatures" }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.waitForText(TankTags.COUNT) { it == "24 creatures" }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            assertEquals("24 creatures", count())
        }
    }
}
