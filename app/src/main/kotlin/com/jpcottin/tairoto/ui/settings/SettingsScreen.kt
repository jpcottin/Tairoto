package com.jpcottin.tairoto.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.QualityTier

object SettingsTags {
    const val SCREEN = "settings_screen"
    const val BACK = "settings_back"
    const val LAGOON = "settings_lagoon"
    const val STATS = "settings_stats"
    const val RESET = "settings_reset"
    const val RESET_CONFIRM = "settings_reset_confirm"
    fun quality(mode: QualityMode) = "settings_quality_" + when (mode) {
        QualityMode.Auto -> "auto"
        is QualityMode.Fixed -> mode.tier.name.lowercase()
    }
}

data class SettingsUi(
    val quality: QualityMode = QualityMode.Auto,
    val activeTier: QualityTier = QualityTier.HIGH,
    val showStats: Boolean = false,
    val creatures: Int = 0,
    val gpu: String = "",
    val version: String = "",
    val tankMm: String = "",
)

@Composable
fun SettingsScreen(
    ui: SettingsUi,
    onBack: () -> Unit,
    onQuality: (QualityMode) -> Unit,
    onShowStats: (Boolean) -> Unit,
    onResetTank: () -> Unit,
    /** Shown beside the lagoon on a wide window rather than on its own. */
    inPane: Boolean = false,
) {
    Scaffold(
        modifier = Modifier.testTag(SettingsTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    // Beside the lagoon it closes, as a pane does; on its own it goes back.
                    IconButton(onClick = onBack, modifier = Modifier.testTag(SettingsTags.BACK)) {
                        if (inPane) Icon(Icons.Rounded.Close, contentDescription = "Close")
                        else Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            SettingsContent(ui, onQuality, onShowStats, onResetTank, Modifier.widthIn(max = 640.dp))
        }
    }
}

@Composable
fun SettingsContent(
    ui: SettingsUi,
    onQuality: (QualityMode) -> Unit,
    onShowStats: (Boolean) -> Unit,
    onResetTank: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("Graphics") {
            Column(Modifier.selectableGroup()) {
                QualityRow(QualityMode.Auto, "Automatic", "Adapts to the frame rate and the device's temperature. Now ${ui.activeTier.label}.", ui.quality, onQuality)
                QualityRow(QualityMode.Fixed(QualityTier.ULTRA), "Ultra", "Full resolution, colour-split caustics and refraction.", ui.quality, onQuality)
                QualityRow(QualityMode.Fixed(QualityTier.HIGH), "High", "Slightly lower resolution, all effects.", ui.quality, onQuality)
                QualityRow(QualityMode.Fixed(QualityTier.LOW), "Low", "For long sessions: cooler and lighter on the battery.", ui.quality, onQuality)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(ui.showStats, role = Role.Switch, onValueChange = onShowStats)
                    .testTag(SettingsTags.STATS)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Performance overlay", style = MaterialTheme.typography.titleMedium)
                    Text("Frame rate, frame time and resolution over the lagoon.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = ui.showStats, onCheckedChange = null)
            }
        }
        Section("Lagoon") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${ui.creatures} creatures in a lagoon of ${ui.tankMm}.",
                    modifier = Modifier.testTag(SettingsTags.LAGOON),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.testTag(SettingsTags.RESET)) {
                    Text("Restore the lagoon")
                }
            }
        }
        Section("About") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tairoto ${ui.version}", style = MaterialTheme.typography.titleMedium)
                Text("A Polynesian lagoon drawn with Vulkan, simulated in C++. Tairoto is Tahitian for lagoon.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.gpu.isNotEmpty()) Text("GPU: ${ui.gpu}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Restore the lagoon?") },
            text = { Text("Every creature in the lagoon is replaced by a school of ʻatoti and one honu.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; onResetTank() }, modifier = Modifier.testTag(SettingsTags.RESET_CONFIRM)) {
                    Text("Restore")
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(PaddingValues(vertical = 4.dp))) { content() }
        }
    }
}

@Composable
private fun QualityRow(mode: QualityMode, title: String, detail: String, selected: QualityMode, onSelect: (QualityMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = mode == selected, role = Role.RadioButton, onClick = { onSelect(mode) })
            .testTag(SettingsTags.quality(mode))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = mode == selected, onClick = null)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(widthDp = 400, heightDp = 900)
@Composable
private fun SettingsPreview() {
    TairotoTheme {
        SettingsScreen(
            SettingsUi(creatures = 23, gpu = "Adreno (TM) 750", version = "0.1.0", tankMm = "190 × 420 mm"),
            onBack = {}, onQuality = {}, onShowStats = {}, onResetTank = {},
        )
    }
}
