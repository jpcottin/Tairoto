package com.jpcottin.tairoto

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.ui.navigation.BottomSheetSceneStrategy
import com.jpcottin.tairoto.ui.settings.SettingsTags
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.world.SpeciesCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The layout follows the window: on a narrow one (a phone, a folded cover
 * screen) the species picker is a sheet over the lagoon and the settings take
 * the screen; on a wide one (a tablet, an unfolded foldable, a desktop window)
 * both open as a pane beside the lagoon, which stays on screen. These tests
 * run on whatever device they are given and check what that window should get.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val creatures = SpeciesCatalog.all.sumOf { it.defaultCount }

    /** Two panes fit from the expanded width class (840 dp) up. */
    private fun twoPanes(scenario: androidx.test.core.app.ActivityScenario<MainActivity>): Boolean {
        var width = 0
        scenario.onActivity { width = it.resources.configuration.screenWidthDp }
        return width >= 840
    }

    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theSpeciesPickerIsASheetWhenNarrowAndAPaneWhenWide() {
        AppLauncher.launch().use { scenario ->
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            val wide = twoPanes(scenario)
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.SHEET)
            assertEquals("pane", wide, present(SheetTags.PANE))
            assertEquals("sheet", !wide, present(BottomSheetSceneStrategy.TAG))
            // The lagoon stays: its title and buttons are still there behind or beside.
            assertTrue(present(TankTags.SURFACE))
            if (wide) {
                // Release straight from the pane; the count beside it goes up.
                compose.onNodeWithTag(SheetTags.release(SpeciesCatalog.HermitCrab.id)).performScrollTo().performClick()
                compose.waitForText(TankTags.COUNT) { it == "${creatures + 1} creatures" }
                compose.onNodeWithTag(SheetTags.CLOSE).performClick()
            } else {
                Espresso.pressBack()
            }
            compose.waitUntil(10_000) { !present(SheetTags.SHEET) }
            assertTrue(present(TankTags.ADD))
        }
    }

    @Test fun settingsOpenBesideTheLagoonWhenWideAndOverItWhenNarrow() {
        AppLauncher.launch().use { scenario ->
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            val wide = twoPanes(scenario)
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.SCREEN)
            assertEquals("the lagoon is still shown", wide, present(TankTags.COUNT))
            compose.waitForText(SettingsTags.LAGOON) { it.startsWith("$creatures creatures") }
            Espresso.pressBack()
            compose.waitUntil(10_000) { !present(SettingsTags.SCREEN) }
            compose.waitForTag(TankTags.COUNT)
        }
    }

    @Test fun openingSettingsReplacesAnOpenPicker() {
        AppLauncher.launch().use { scenario ->
            compose.waitForText(TankTags.COUNT) { it == "$creatures creatures" }
            if (!twoPanes(scenario)) return@use  // on a narrow window the sheet covers the settings button
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.PANE)
            compose.onNodeWithTag(TankTags.SETTINGS).performClick()
            compose.waitForTag(SettingsTags.SCREEN)
            compose.waitUntil(10_000) { !present(SheetTags.PANE) }
            // One back closes the settings; the picker is not waiting behind it.
            Espresso.pressBack()
            compose.waitUntil(10_000) { !present(SettingsTags.SCREEN) }
            assertTrue(!present(SheetTags.PANE))
        }
    }
}
