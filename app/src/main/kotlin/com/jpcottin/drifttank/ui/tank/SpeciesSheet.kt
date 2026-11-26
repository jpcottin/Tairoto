package com.jpcottin.drifttank.ui.tank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.drifttank.ui.species.SpeciesArt
import com.jpcottin.drifttank.ui.theme.DriftTankTheme
import com.jpcottin.drifttank.ui.theme.Ocean
import com.jpcottin.drifttank.world.Locomotion
import com.jpcottin.drifttank.world.Species
import com.jpcottin.drifttank.world.SpeciesCatalog

object SheetTags {
    const val SHEET = "species_sheet"
    fun card(id: String) = "species_card_$id"
    fun release(id: String) = "species_release_$id"
    fun count(id: String) = "species_count_$id"
}

@Composable
fun SpeciesSheet(counts: Map<String, Int>, roomLeft: Int, onRelease: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag(SheetTags.SHEET),
    ) {
        SpeciesSheetContent(counts, roomLeft, onRelease)
    }
}

@Composable
fun SpeciesSheetContent(counts: Map<String, Int>, roomLeft: Int, onRelease: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Release into the tank", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (roomLeft > 0) "They are released where you are looking." else "The tank is full.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SpeciesCatalog.all.forEach { species ->
            SpeciesCard(species, counts[species.id] ?: 0, enabled = roomLeft > 0, onRelease = { onRelease(species.id) })
        }
    }
}

private fun Species.traits(): List<String> = buildList {
    add(tankType.name.lowercase().replaceFirstChar { it.uppercase() })
    if (schooling > 0.5f) add("Schools")
    if (locomotion == Locomotion.PADDLE) {
        add("Rests on the sand")
        add("Breathes at the surface")
    }
    if (shyness > 0.6f) add("Shy")
}

@Composable
fun SpeciesCard(species: Species, inTank: Int, enabled: Boolean, onRelease: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag(SheetTags.card(species.id)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeciesArt(species.id, Modifier.size(88.dp).clip(CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(species.commonName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        species.scientificName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        species.traits().forEach { Trait(it) }
                    }
                }
            }
            Text(species.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inTank == 0) "None in the tank" else "$inTank in the tank",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ocean.Mist,
                    modifier = Modifier.weight(1f).testTag(SheetTags.count(species.id)),
                )
                FilledTonalButton(onClick = onRelease, enabled = enabled, modifier = Modifier.testTag(SheetTags.release(species.id))) {
                    Text(if (species.addBatch == 1) "Release one" else "Release ${species.addBatch}")
                }
            }
        }
    }
}

@Composable
private fun Trait(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Preview(widthDp = 400, heightDp = 900)
@Composable
private fun SpeciesSheetPreview() {
    DriftTankTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpeciesSheetContent(mapOf("green_chromis" to 22, "green_sea_turtle" to 1), roomLeft = 137, onRelease = {})
        }
    }
}
