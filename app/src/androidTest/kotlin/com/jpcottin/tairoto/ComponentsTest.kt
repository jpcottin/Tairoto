package com.jpcottin.tairoto

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.ui.settings.SettingsContent
import com.jpcottin.tairoto.ui.settings.SettingsTags
import com.jpcottin.tairoto.ui.settings.SettingsUi
import com.jpcottin.tairoto.ui.tank.SheetTags
import com.jpcottin.tairoto.ui.tank.SpeciesSheetContent
import com.jpcottin.tairoto.ui.tank.TankHud
import com.jpcottin.tairoto.ui.tank.TankTags
import com.jpcottin.tairoto.ui.tank.TankUiState
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Composables on their own, without the engine. */
@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theSheetListsEverySpeciesAndReleasesTheOneTapped() {
        val released = mutableListOf<String>()
        compose.setContent {
            TairotoTheme { SpeciesSheetContent(mapOf("green_chromis" to 22, "green_sea_turtle" to 1), 137) { released += it } }
        }
        compose.onNodeWithText("ʻAtoti").assertIsDisplayed()
        compose.onNodeWithText("Green chromis · Chromis viridis").assertIsDisplayed()
        compose.onNodeWithText("Honu").assertIsDisplayed()
        compose.onNodeWithTag(SheetTags.count("green_chromis")).assertIsDisplayed()
        compose.onNodeWithText("22 in the lagoon").assertIsDisplayed()
        compose.onNodeWithTag(SheetTags.release("green_sea_turtle")).performScrollTo().performClick()
        compose.onNodeWithTag(SheetTags.release("green_chromis")).performScrollTo().performClick()
        assertEquals(listOf("green_sea_turtle", "green_chromis"), released)
    }

    @Test fun aFullTankDisablesRelease() {
        compose.setContent { TairotoTheme { SpeciesSheetContent(mapOf("green_chromis" to 160), 0) {} } }
        compose.onNodeWithText("The lagoon is full.").assertIsDisplayed()
        compose.onNodeWithTag(SheetTags.release("green_chromis")).assertIsNotEnabled()
        compose.onNodeWithText("None in the lagoon").assertIsDisplayed()
    }

    @Test fun choosingAQualityReportsIt() {
        val picked = mutableListOf<QualityMode>()
        compose.setContent { TairotoTheme { SettingsContent(SettingsUi(), { picked += it }, {}, {}) } }
        compose.onNodeWithTag(SettingsTags.quality(QualityMode.Fixed(QualityTier.ULTRA))).performClick()
        compose.onNodeWithTag(SettingsTags.quality(QualityMode.Auto)).performClick()
        assertEquals(listOf(QualityMode.Fixed(QualityTier.ULTRA), QualityMode.Auto), picked)
    }

    @Test fun automaticShowsTheTierInUse() {
        compose.setContent { TairotoTheme { SettingsContent(SettingsUi(activeTier = QualityTier.LOW), {}, {}, {}) } }
        compose.onNodeWithText("Adapts to the frame rate and the device's temperature. Now Low.").assertIsDisplayed()
    }

    @Test fun theOverlaySwitchToggles() {
        val values = mutableListOf<Boolean>()
        compose.setContent { TairotoTheme { SettingsContent(SettingsUi(showStats = false), {}, { values += it }, {}) } }
        compose.onNodeWithTag(SettingsTags.STATS).performClick()
        assertEquals(listOf(true), values)
    }

    @Test fun restoringTheReefAsksFirst() {
        var resets = 0
        compose.setContent { TairotoTheme { SettingsContent(SettingsUi(), {}, {}, { resets++ }) } }
        compose.onNodeWithTag(SettingsTags.RESET).performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, resets)
        compose.onNodeWithTag(SettingsTags.RESET).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTags.RESET_CONFIRM).performClick()
        assertEquals(1, resets)
    }

    @Test fun theHudCountsInTheSingular() {
        compose.setContent { TairotoTheme { Box { TankHud(TankUiState(ready = true, total = 1), {}, {}) } } }
        compose.onNodeWithTag(TankTags.COUNT).assertIsDisplayed()
        compose.onNodeWithText("1 creature").assertIsDisplayed()
    }

    @Test fun theHudExplainsAMissingVulkan() {
        compose.setContent { TairotoTheme { Box { TankHud(TankUiState(vulkanUnsupported = true), {}, {}) } } }
        compose.onNodeWithTag(TankTags.UNSUPPORTED).assertIsDisplayed()
    }

    @Test fun theHudButtonsCallBack() {
        var adds = 0
        var settings = 0
        compose.setContent { TairotoTheme { Box { TankHud(TankUiState(ready = true), { adds++ }, { settings++ }) } } }
        compose.onNodeWithTag(TankTags.ADD).performClick()
        compose.onNodeWithTag(TankTags.SETTINGS).performClick()
        assertEquals(1, adds)
        assertEquals(1, settings)
    }

    @Test fun aLongGpuNameNeverPushesTheButtonsAway() {
        compose.setContent {
            TairotoTheme {
                Box {
                    TankHud(TankUiState(ready = true, showStats = true, gpu = "Virtio-GPU GFXStream (llvmpipe (LLVM 21.1.8, 256 bits)) with a very long name"), {}, {})
                }
            }
        }
        compose.onNodeWithTag(TankTags.ADD).assertIsDisplayed()
        compose.onNodeWithTag(TankTags.SETTINGS).assertIsDisplayed()
    }

    @Test fun theStatsPanelShowsTheFigures() {
        compose.setContent {
            TairotoTheme {
                Box {
                    TankHud(TankUiState(ready = true, showStats = true, tier = QualityTier.ULTRA,
                        stats = EngineStats(fps = 59.6f, workMs = 2.25f, creatures = 23, drawn = 20, frames = 99, sceneWidth = 1080, sceneHeight = 2400, width = 1080, height = 2400)), {}, {})
                }
            }
        }
        val text = compose.onNodeWithTag(TankTags.STATS).text()
        assertTrue(text, text.contains("60 fps") && text.contains("2.3 ms") && text.contains("Ultra") && text.contains("20/23 drawn"))
    }
}
