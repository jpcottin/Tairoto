package com.jpcottin.tairoto

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.ui.tank.RemoteActions
import com.jpcottin.tairoto.ui.tank.RemoteCursorRing
import com.jpcottin.tairoto.ui.tank.RemoteState
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.ui.tank.remoteControl
import com.jpcottin.tairoto.world.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The lagoon driven by keys: a TV remote, a keyboard, a controller. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RemoteControlTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : RemoteActions {
        val taps = mutableListOf<Vec2>()
        val pellets = mutableListOf<Vec2>()
        val sprinkles = mutableListOf<Vec2>()
        val zooms = mutableListOf<Float>()
        override fun tap(at: Vec2) { taps += at }
        override fun pellet(at: Vec2) { pellets += at }
        override fun sprinkle(at: Vec2) { sprinkles += at }
        override fun pan(delta: Vec2) = Unit
        override fun zoom(at: Vec2, factor: Float) { zooms += factor }
    }

    @Test fun arrowsMoveTheCursorAndOkTapsTheGlassThere() {
        val r = Recorder()
        val state = RemoteState()
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("water").remoteControl(state, r, LocalFocusManager.current))
            RemoteCursorRing(state)
        }
        compose.runOnIdle { state.focus.requestFocus() }
        compose.onNodeWithTag("water").performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        compose.onNodeWithTag(TankTags.CURSOR).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, r.taps.size)
            val at = state.at!!
            assertTrue("moved right and down from the middle: $at", r.taps[0] == at)
        }
        compose.onNodeWithTag("water").performKeyInput {
            pressKey(Key.F)
            pressKey(Key.Plus)
            pressKey(Key.Minus)
        }
        compose.runOnIdle {
            assertEquals(1, r.sprinkles.size)
            assertEquals(2, r.zooms.size)
            assertTrue(r.zooms[0] > 1f && r.zooms[1] < 1f)
        }
    }

    @Test fun holdingOkDropsAPelletAndNoTap() {
        val r = Recorder()
        val state = RemoteState()
        compose.setContent { Box(Modifier.fillMaxSize().testTag("water").remoteControl(state, r, LocalFocusManager.current)) }
        compose.runOnIdle { state.focus.requestFocus() }
        compose.onNodeWithTag("water").performKeyInput {
            withKeyDown(Key.DirectionCenter) { advanceEventTime(1_500) }
        }
        compose.runOnIdle {
            assertEquals(1, r.pellets.size)
            assertEquals(0, r.taps.size)
        }
    }
}
