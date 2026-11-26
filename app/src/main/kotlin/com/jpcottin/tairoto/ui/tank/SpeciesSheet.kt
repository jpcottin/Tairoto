package com.jpcottin.tairoto.ui.tank

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.ui.species.DecorArt
import com.jpcottin.tairoto.ui.species.SpeciesArt
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.ui.theme.Ocean
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.DecorKind
import com.jpcottin.tairoto.world.Locomotion
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.Species
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankType

object SheetTags {
    const val SHEET = "species_sheet"
    const val PANE = "species_pane"
    const val CLOSE = "species_close"
    fun card(id: String) = "species_card_$id"
    fun release(id: String) = "species_release_$id"
    fun count(id: String) = "species_count_$id"
    const val TAB_CREATURES = "picker_tab_creatures"
    const val TAB_DECOR = "picker_tab_decor"
    const val DECOR = "decor_sheet"
    fun decorCard(id: String) = "decor_card_$id"
    fun place(id: String) = "decor_place_$id"
    fun decorCount(id: String) = "decor_count_$id"
}

/**
 * What can go into the lagoon: creatures to release, and decorations to put
 * on the sand, on two tabs. On a narrow window it is the content of a bottom
 * sheet over the lagoon; on a wide one a pane beside it, with [onClose].
 */
@Composable
fun SpeciesPicker(
    counts: Map<String, Int>,
    roomLeft: Int,
    onRelease: (String) -> Unit,
    onClose: (() -> Unit)?,
    decorCounts: Map<String, Int> = emptyMap(),
    decorRoomLeft: Int = TankScene.MAX_DECOR,
    onPlace: (String) -> Unit = {},
    startOnDecor: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(if (startOnDecor) 1 else 0) }
    val content: @Composable () -> Unit = {
        Column {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Creatures") }, modifier = Modifier.testTag(SheetTags.TAB_CREATURES))
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Decorations") }, modifier = Modifier.testTag(SheetTags.TAB_DECOR))
            }
            if (tab == 0) SpeciesSheetContent(counts, roomLeft, onRelease)
            else DecorSheetContent(decorCounts, decorRoomLeft, onPlace)
        }
    }
    if (onClose == null) {
        content()
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxSize().testTag(SheetTags.PANE),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag(SheetTags.CLOSE)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close")
                }
            }
            content()
        }
    }
}

@Composable
fun SpeciesSheetContent(counts: Map<String, Int>, roomLeft: Int, onRelease: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(SheetTags.SHEET)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Release into the lagoon", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (roomLeft > 0) "They are released where you are looking." else "The lagoon is full.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SpeciesCatalog.all.forEach { species ->
            SpeciesCard(species, counts[species.id] ?: 0, enabled = roomLeft > 0, onRelease = { onRelease(species.id) })
        }
    }
}

@Composable
fun DecorSheetContent(counts: Map<String, Int>, roomLeft: Int, onPlace: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(SheetTags.DECOR)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Furnish the lagoon floor", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (roomLeft > 0) "Placed where you are looking. Then drag it into place, twist it with two fingers, or net a creature."
            else "The floor is full: remove something first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DecorCatalog.all.forEach { kind ->
            DecorCard(kind, counts[kind.id] ?: 0, enabled = roomLeft > 0, onPlace = { onPlace(kind.id) })
        }
    }
}

@Composable
fun DecorCard(kind: DecorKind, onFloor: Int, enabled: Boolean, onPlace: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag(SheetTags.decorCard(kind.id)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DecorArt(kind.id, Modifier.size(88.dp).clip(CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(kind.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(kind.commonName, kind.scientificName).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        kind.traits.forEach { Trait(it) }
                    }
                }
            }
            Text(kind.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (onFloor == 0) "None on the floor" else "$onFloor on the floor",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ocean.Mist,
                    modifier = Modifier.weight(1f).testTag(SheetTags.decorCount(kind.id)),
                )
                FilledTonalButton(onClick = onPlace, enabled = enabled, modifier = Modifier.testTag(SheetTags.place(kind.id))) {
                    Text("Place one")
                }
            }
        }
    }
}

private fun Species.traits(): List<String> = buildList {
    add(if (tankType == TankType.REEF) "Lagoon" else tankType.name.lowercase().replaceFirstChar { it.uppercase() })
    if (schooling > 0.5f) add("Schools")
    when (locomotion) {
        Locomotion.SWIM -> Unit
        Locomotion.PADDLE -> {
            add("Rests on the sand")
            add("Breathes at the surface")
        }
        Locomotion.GLIDE -> {
            add("Flies on its wings")
            add("Hides under the sand")
        }
        Locomotion.CRAWL -> {
            add("Walks on eight arms")
            add("Camouflage")
            add("Inks")
        }
        Locomotion.WALK -> {
            add("Carries its shell")
            add("Hides in it")
        }
    }
    if (bottomFeeder && locomotion != Locomotion.PADDLE) add("Feeds on the sand")
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
                    Text(species.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (species.tahitianName != null) "${species.commonName} · ${species.scientificName}" else species.scientificName,
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
                    if (inTank == 0) "None in the lagoon" else "$inTank in the lagoon",
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
    TairotoTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            SpeciesSheetContent(mapOf("green_chromis" to 22, "green_sea_turtle" to 1), roomLeft = 137, onRelease = {})
        }
    }
}
