package com.jpcottin.tairoto

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.ui.draw.DrawActions
import com.jpcottin.tairoto.ui.draw.DrawSheet
import com.jpcottin.tairoto.ui.draw.DrawTags
import com.jpcottin.tairoto.ui.preview.PreviewData
import com.jpcottin.tairoto.ui.tank.DrawPhase
import com.jpcottin.tairoto.ui.tank.DrawUi
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.SpeciesSheetContent
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sheet for drawing a fish of one's own, on its own. */
@RunWith(AndroidJUnit4::class)
class DrawSheetTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : DrawActions {
        val calls = mutableListOf<String>()
        override fun print() { calls += "print" }
        override fun takePhoto() { calls += "camera" }
        override fun choosePhoto() { calls += "choose" }
        override fun release() { calls += "release" }
        override fun again() { calls += "again" }
    }

    @Test fun theStepsLeadToPrintingAndToAPhoto() {
        val r = Recorder()
        compose.setContent { TairotoTheme { DrawSheet(DrawUi(), r, onClose = null) } }
        compose.onNodeWithText("Draw your own fish").assertIsDisplayed()
        compose.onNodeWithTag(DrawTags.PRINT).performScrollTo().performClick()
        compose.onNodeWithTag(DrawTags.CAMERA).performScrollTo().performClick()
        compose.onNodeWithTag(DrawTags.CHOOSE).performScrollTo().performClick()
        assertEquals(listOf("print", "camera", "choose"), r.calls)
        compose.onNodeWithText("No drawn fish in the lagoon yet.").performScrollTo().assertIsDisplayed()
    }

    @Test fun aFishFoundIsShownAndLetGoOrTriedAgain() {
        val r = Recorder()
        var ui by mutableStateOf(DrawUi(phase = DrawPhase.SCANNING))
        compose.setContent { TairotoTheme { DrawSheet(ui, r, onClose = null) } }
        compose.onNodeWithTag(DrawTags.SCANNING).assertIsDisplayed()
        compose.onNodeWithTag(DrawTags.CAMERA).performScrollTo().assertIsNotEnabled()
        ui = PreviewData.drawFound
        compose.onNodeWithTag(DrawTags.PREVIEW).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(DrawTags.RELEASE).performScrollTo().performClick()
        compose.onNodeWithTag(DrawTags.AGAIN).performScrollTo().performClick()
        assertEquals(listOf("release", "again"), r.calls)
        // No room: it cannot go in, and says why.
        ui = PreviewData.drawFound.copy(phase = DrawPhase.FULL, room = false)
        compose.onNodeWithTag(DrawTags.RELEASE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(DrawTags.MESSAGE).performScrollTo().assertIsDisplayed()
    }

    @Test fun aPhotoWithoutThePageSaysWhatToDo() {
        compose.setContent { TairotoTheme { DrawSheet(DrawUi(phase = DrawPhase.NO_PAGE), Recorder(), onClose = null) } }
        compose.onNodeWithTag(DrawTags.MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("weren't all in the photo", substring = true).assertIsDisplayed()
    }

    @Test fun theSpeciesSheetLeadsToDrawing() {
        var opened = 0
        compose.setContent { TairotoTheme { SpeciesSheetContent(emptyMap(), 100, onRelease = {}, onDraw = { opened++ }) } }
        compose.onNodeWithTag(SheetTags.DRAW).performScrollTo().performClick()
        assertEquals(1, opened)
    }
}
