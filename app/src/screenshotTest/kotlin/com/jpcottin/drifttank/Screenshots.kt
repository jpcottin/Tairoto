package com.jpcottin.drifttank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.jpcottin.drifttank.engine.EngineStats
import com.jpcottin.drifttank.engine.VulkanState
import com.jpcottin.drifttank.ui.settings.SettingsScreen
import com.jpcottin.drifttank.ui.settings.SettingsUi
import com.jpcottin.drifttank.ui.species.SpeciesArt
import com.jpcottin.drifttank.ui.tank.SpeciesSheetContent
import com.jpcottin.drifttank.ui.tank.TankHud
import com.jpcottin.drifttank.ui.tank.TankUiState
import com.jpcottin.drifttank.ui.theme.DriftTankTheme
import com.jpcottin.drifttank.ui.theme.Ocean
import com.jpcottin.drifttank.world.QualityMode
import com.jpcottin.drifttank.world.QualityTier

/*
 * The Compose screens, rendered by the screenshot tests and compared with the
 * references in src/screenshotTestDebug/reference. The tank itself is Vulkan
 * and is checked on a device by the instrumented tests; here it is stood in
 * for by a plain gradient so the HUD over it can be judged.
 */

@Composable
private fun WaterBackdrop(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ocean.Teal, Ocean.Deep)))) { content() }
}

private val busyStats = EngineStats(
    fps = 60f, workMs = 2.4f, creatures = 23, drawn = 19, frames = 1840, vulkan = VulkanState.RENDERING,
    sceneWidth = 918, sceneHeight = 2040, width = 1080, height = 2400,
)

@PreviewTest
@Preview(name = "phone", widthDp = 411, heightDp = 891)
@Composable
fun HudPhone() {
    DriftTankTheme { WaterBackdrop { TankHud(TankUiState(ready = true, total = 23, hintVisible = true), {}, {}) } }
}

@PreviewTest
@Preview(name = "phone stats", widthDp = 411, heightDp = 891)
@Composable
fun HudWithStats() {
    DriftTankTheme {
        WaterBackdrop {
            TankHud(
                TankUiState(ready = true, total = 23, hintVisible = false, showStats = true, stats = busyStats,
                    tier = QualityTier.HIGH, gpu = "Virtio-GPU GFXStream (llvmpipe (LLVM 21.1.8, 256 bits))"),
                {}, {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "tablet", widthDp = 1280, heightDp = 800)
@Composable
fun HudTablet() {
    DriftTankTheme { WaterBackdrop { TankHud(TankUiState(ready = true, total = 160, hintVisible = true), {}, {}) } }
}

@PreviewTest
@Preview(name = "no vulkan", widthDp = 411, heightDp = 891)
@Composable
fun HudNoVulkan() {
    DriftTankTheme { WaterBackdrop { TankHud(TankUiState(ready = false, vulkanUnsupported = true, hintVisible = false), {}, {}) } }
}

@PreviewTest
@Preview(name = "sheet", widthDp = 411, heightDp = 891)
@Composable
fun SpeciesSheetScreenshot() {
    DriftTankTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpeciesSheetContent(mapOf("green_chromis" to 22, "green_sea_turtle" to 1), roomLeft = 137, onRelease = {})
        }
    }
}

@PreviewTest
@Preview(name = "sheet full", widthDp = 411, heightDp = 891)
@Composable
fun SpeciesSheetFull() {
    DriftTankTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpeciesSheetContent(mapOf("green_chromis" to 159, "green_sea_turtle" to 1), roomLeft = 0, onRelease = {})
        }
    }
}

@PreviewTest
@Preview(name = "settings", widthDp = 411, heightDp = 891)
@Composable
fun SettingsPhone() {
    DriftTankTheme {
        SettingsScreen(
            SettingsUi(quality = QualityMode.Auto, activeTier = QualityTier.HIGH, showStats = true, creatures = 23,
                gpu = "Adreno (TM) 750", version = "0.1.0", tankMm = "190 × 420 mm"),
            onBack = {}, onQuality = {}, onShowStats = {}, onResetTank = {},
        )
    }
}

@PreviewTest
@Preview(name = "settings tablet", widthDp = 1280, heightDp = 800)
@Composable
fun SettingsTablet() {
    DriftTankTheme {
        SettingsScreen(
            SettingsUi(quality = QualityMode.Fixed(QualityTier.ULTRA), activeTier = QualityTier.ULTRA, creatures = 61,
                gpu = "Mali-G715", version = "0.1.0", tankMm = "420 × 330 mm"),
            onBack = {}, onQuality = {}, onShowStats = {}, onResetTank = {},
        )
    }
}

@PreviewTest
@Preview(name = "species art", widthDp = 280, heightDp = 140)
@Composable
fun SpeciesArtScreenshot() {
    DriftTankTheme {
        Row(Modifier.background(Ocean.Abyss).padding(10.dp)) {
            SpeciesArt("green_chromis", Modifier.size(120.dp).clip(CircleShape))
            Box(Modifier.size(20.dp))
            SpeciesArt("green_sea_turtle", Modifier.size(120.dp).clip(CircleShape))
        }
    }
}
