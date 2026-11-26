package com.jpcottin.tairoto

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.ui.tank.EditTags
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.SpeciesCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Arranging the lagoon on a device: the edit bar, placing and undoing decorations, the tools, keeping the lagoon. */
@RunWith(AndroidJUnit4::class)
class EditModeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val DEFAULT = SpeciesCatalog.all.sumOf { it.defaultCount }

    private fun isSelected(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.Selected, on)

    private fun waitGone(tag: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    @Test fun arrangingSwapsTheHudForTheEditBarAndBack() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$DEFAULT creatures" }
            compose.onNodeWithTag(EditTags.ARRANGE).performClick()
            compose.waitForTag(EditTags.BAR)
            compose.onNodeWithTag(EditTags.DONE).assertIsDisplayed()
            compose.onNodeWithTag(EditTags.UNDO).assertIsNotEnabled()
            compose.onNodeWithTag(EditTags.REDO).assertIsNotEnabled()
            compose.onNodeWithTag(EditTags.MOVE).assert(isSelected(true))
            compose.onNodeWithTag(EditTags.HINT).assertIsDisplayed()
            compose.onNodeWithTag(EditTags.NET).performClick()
            compose.onNodeWithTag(EditTags.NET).assert(isSelected(true))
            compose.onNodeWithTag(EditTags.MOVE).assert(isSelected(false))
            compose.onNodeWithTag(EditTags.DONE).performClick()
            compose.waitForTag(TankTags.ADD)
            waitGone(EditTags.BAR)
        }
    }

    @Test fun backFinishesArranging() {
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$DEFAULT creatures" }
            compose.onNodeWithTag(EditTags.ARRANGE).performClick()
            compose.waitForTag(EditTags.BAR)
            Espresso.pressBack()
            compose.waitForTag(TankTags.ADD)
            waitGone(EditTags.BAR)
        }
    }

    @Test fun placingAClamSelectsItAndUndoTakesItAway() {
        val clam = DecorCatalog.GiantClam.id
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$DEFAULT creatures" }
            compose.onNodeWithTag(TankTags.ADD).performClick()
            compose.waitForTag(SheetTags.TAB_DECOR)
            compose.onNodeWithTag(SheetTags.TAB_DECOR).performClick()
            compose.waitForTag(SheetTags.place(clam))
            val before = compose.onNodeWithTag(SheetTags.decorCount(clam)).performScrollTo().text()
            compose.onNodeWithTag(SheetTags.place(clam)).performScrollTo().performClick()
            // On a phone the sheet closes so the clam can be seen; it is selected, ready to drag.
            compose.waitForTag(EditTags.SELECTION)
            compose.waitForText(EditTags.SELECTION) { it.contains("Pahua") }
            compose.onNodeWithTag(EditTags.UNDO).assertIsEnabled()
            compose.onNodeWithTag(EditTags.RING).assertExists()
            compose.onNodeWithTag(EditTags.UNDO).performClick()
            waitGone(EditTags.SELECTION)
            compose.onNodeWithTag(EditTags.REDO).assertIsEnabled()
            // The count on the card is back where it was.
            compose.onNodeWithTag(EditTags.ADD).performClick()
            compose.waitForTag(SheetTags.decorCount(clam))
            assertEquals(before, compose.onNodeWithTag(SheetTags.decorCount(clam)).performScrollTo().text())
        }
    }

    @Test fun aSelectedDecorationCanBeTurnedAndTakenAway() {
        val stone = DecorCatalog.Stone.id
        AppLauncher.launch().use {
            compose.waitForText(TankTags.COUNT) { it == "$DEFAULT creatures" }
            compose.onNodeWithTag(EditTags.ARRANGE).performClick()
            compose.onNodeWithTag(EditTags.ADD).performClick()
            compose.waitForTag(SheetTags.place(stone))
            compose.onNodeWithTag(SheetTags.place(stone)).performScrollTo().performClick()
            compose.waitForText(EditTags.SELECTION) { it.contains("ʻŌfaʻi") }
            compose.onNodeWithTag(EditTags.TURN).performClick()
            compose.onNodeWithTag(EditTags.REMOVE).performClick()
            waitGone(EditTags.SELECTION)
            compose.onNodeWithTag(EditTags.UNDO).assertIsEnabled()
            compose.onNodeWithTag(EditTags.UNDO).performClick()
            // Undoing the removal brings it back (not selected).
            compose.onNodeWithTag(EditTags.UNDO).assertIsEnabled()
        }
    }

    @Test fun theLagoonIsKeptBetweenLaunches() {
        // Not a fresh lagoon: whatever was saved on this device, then one more turtle.
        val first = AppLauncher.launch(ci = false, fresh = false)
        compose.waitUntil(25_000) { compose.onAllNodes(hasTestTag(TankTags.COUNT)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onNodeWithTag(TankTags.COUNT).text().endsWith("creatures") || compose.onNodeWithTag(TankTags.COUNT).text().endsWith("creature") }
        val before = compose.onNodeWithTag(TankTags.COUNT).text().substringBefore(' ').toInt()
        if (before >= 160) return first.close()
        compose.onNodeWithTag(TankTags.ADD).performClick()
        compose.waitForTag(SheetTags.release(SpeciesCatalog.GreenSeaTurtle.id))
        compose.onNodeWithTag(SheetTags.release(SpeciesCatalog.GreenSeaTurtle.id)).performScrollTo().performClick()
        Espresso.pressBack()
        compose.waitForText(TankTags.COUNT) { it.startsWith("${before + 1} ") }
        first.close()
        Thread.sleep(1_500)  // the save is written as the app goes away
        AppLauncher.launch(ci = false, fresh = false).use {
            compose.waitForText(TankTags.COUNT) { it.startsWith("${before + 1} ") }
        }
    }
}
