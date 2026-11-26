package com.jpcottin.tairoto.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.engine.VulkanState
import com.jpcottin.tairoto.ui.navigation.rememberLagoonDirective
import com.jpcottin.tairoto.ui.settings.SettingsScreen
import com.jpcottin.tairoto.ui.settings.SettingsUi
import com.jpcottin.tairoto.ui.species.DecorArt
import com.jpcottin.tairoto.ui.species.SpeciesArt
import com.jpcottin.tairoto.ui.tank.DecorCard
import com.jpcottin.tairoto.ui.tank.EditActions
import com.jpcottin.tairoto.ui.tank.EditOverlay
import com.jpcottin.tairoto.ui.tank.EditTool
import com.jpcottin.tairoto.ui.tank.EditUi
import com.jpcottin.tairoto.ui.tank.SelectionRing
import com.jpcottin.tairoto.ui.tank.SelectionUi
import com.jpcottin.tairoto.ui.tank.SpeciesCard
import com.jpcottin.tairoto.ui.tank.SpeciesPicker
import com.jpcottin.tairoto.ui.tank.TankHud
import com.jpcottin.tairoto.ui.tank.TankUiState
import com.jpcottin.tairoto.ui.theme.Ocean
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.world.DecorCatalog
import com.jpcottin.tairoto.world.EntityId
import com.jpcottin.tairoto.world.QualityMode
import com.jpcottin.tairoto.world.UndoState
import com.jpcottin.tairoto.world.Vec2
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankSize

/**
 * Sample states and stand-ins shared by the previews in the IDE and by the
 * screenshot tests, so both show the same, realistic content. The lagoon
 * itself is drawn by Vulkan on a device; here a painted backdrop stands in for
 * it, so the layout over and beside it can be judged.
 */
object PreviewData {
    val counts = SpeciesCatalog.all.associate { it.id to it.defaultCount }
    val total = counts.values.sum()

    val stats = EngineStats(
        fps = 60f, workMs = 2.4f, creatures = total, drawn = 21, frames = 1840, vulkan = VulkanState.RENDERING,
        sceneWidth = 918, sceneHeight = 2040, width = 1080, height = 2400,
    )

    val lagoon = TankUiState(
        ready = true, counts = counts, total = total, roomLeft = 160 - total, hintVisible = true,
        tankSize = TankSize(190f, 420f, 55f),
    )

    val lagoonWithStats = lagoon.copy(
        hintVisible = false, showStats = true, stats = stats, tier = QualityTier.HIGH, gpu = "Adreno (TM) 750",
        thermalHeadroom = 0.42f,
    )

    val decorCounts = mapOf("coral_head" to 2, "branching_coral" to 1, "giant_clam" to 1, "stone" to 3)

    /** Arranging, with a coral head selected in the middle of the screen. */
    fun editing(tool: EditTool = EditTool.MOVE, selected: Boolean = true) = EditUi(
        active = true,
        tool = tool,
        selection = if (selected) SelectionUi(EntityId(9), Vec2(520f, 1100f), 260f, "Toʻa") else null,
        undo = UndoState(undo = "moving Toʻa"),
    )

    val settings = SettingsUi(
        quality = QualityMode.Auto, activeTier = QualityTier.HIGH, showStats = true, creatures = total,
        gpu = "Adreno (TM) 750", version = "0.3.0", tankMm = "190 × 420 mm",
    )
}

/** Painted sand and water with a few caustic lines, where the Vulkan lagoon would be. */
@Composable
fun LagoonBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF5E9E97), Color(0xFF3F7E7A))))) {
        val light = Color(0x40E6FBF6)
        val step = size.minDimension / 5f
        var y = step * 0.4f
        var k = 0
        while (y < size.height) {
            val p = Path()
            p.moveTo(0f, y)
            p.cubicTo(size.width * 0.3f, y - step * 0.3f, size.width * 0.6f, y + step * 0.35f, size.width, y - step * 0.1f * (k % 3))
            drawPath(p, light, style = Stroke(width = 2.5f))
            y += step * 0.55f
            k++
        }
        drawCircle(Color(0x33041A20), radius = step * 0.5f, center = Offset(size.width * 0.3f, size.height * 0.6f))
    }
}

/** The lagoon screen as it looks with nothing open: the backdrop and the HUD over it. */
@Composable
fun LagoonScreenPreviewContent(state: TankUiState = PreviewData.lagoon) {
    Box(Modifier.fillMaxSize()) {
        LagoonBackdrop()
        TankHud(state, onAdd = {}, onSettings = {})
    }
}

/**
 * The lagoon with a supporting place open, laid out the way the app decides:
 * beside the lagoon when two panes fit the window, over it (a sheet) or
 * instead of it when they do not. The same directive as TairotoNavigation.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LagoonWithPanePreviewContent(pane: PreviewPane, state: TankUiState = PreviewData.lagoon) {
    val directive = rememberLagoonDirective()
    val twoPane = directive.maxHorizontalPartitions > 1
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) { LagoonScreenPreviewContent(state.copy(hintVisible = false)) }
            Box(Modifier.width(directive.defaultPanePreferredWidth).fillMaxHeight()) { PaneContent(pane, inPane = true) }
        }
        return
    }
    when (pane) {
        PreviewPane.Species, PreviewPane.Decor -> Box(Modifier.fillMaxSize()) {
            LagoonScreenPreviewContent(state.copy(hintVisible = false))
            Box(Modifier.fillMaxSize().background(Color(0x66000000)))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.8f),
            ) { Column(Modifier.padding(top = 22.dp)) { PaneContent(pane, inPane = false) } }
        }
        PreviewPane.Settings -> PaneContent(pane, inPane = false)
    }
}

enum class PreviewPane { Species, Decor, Settings }

@Composable
private fun PaneContent(pane: PreviewPane, inPane: Boolean) {
    when (pane) {
        PreviewPane.Species -> SpeciesPicker(PreviewData.counts, 160 - PreviewData.total, onRelease = {}, onClose = if (inPane) ({}) else null)
        PreviewPane.Decor -> SpeciesPicker(
            PreviewData.counts, 160 - PreviewData.total, onRelease = {}, onClose = if (inPane) ({}) else null,
            decorCounts = PreviewData.decorCounts, decorRoomLeft = 17, startOnDecor = true,
        )
        PreviewPane.Settings -> SettingsScreen(PreviewData.settings, onBack = {}, onQuality = {}, onShowStats = {}, onResetTank = {}, inPane = inPane)
    }
}

/**
 * Arranging the lagoon: the edit bar over the backdrop, with a decoration
 * selected (its ring and its name with the turn and remove buttons) or the
 * hint for the tool in hand.
 */
@Composable
fun LagoonEditingPreviewContent(edit: EditUi = PreviewData.editing()) {
    Box(Modifier.fillMaxSize()) {
        LagoonBackdrop()
        SelectionRing(edit.selection)
        EditOverlay(
            edit,
            object : EditActions {
                override fun done() = Unit
                override fun undo() = Unit
                override fun redo() = Unit
                override fun tool(tool: EditTool) = Unit
                override fun add() = Unit
                override fun turn() = Unit
                override fun remove() = Unit
            },
            Modifier.padding(14.dp),
        )
    }
}

/** One card per decoration, as in the picker. */
@Composable
fun DecorCardsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DecorCatalog.all.forEach { DecorCard(it, PreviewData.decorCounts[it.id] ?: 0, enabled = true, onPlace = {}) }
        }
    }
}

/** Every decoration's portrait with its Tahitian name. */
@Composable
fun DecorGalleryPreviewContent() {
    Row(Modifier.background(Ocean.Abyss).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DecorCatalog.all.forEach { kind ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DecorArt(kind.id, Modifier.size(104.dp).clip(CircleShape))
                Text(
                    kind.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = Ocean.Foam,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(104.dp).padding(top = 4.dp),
                )
            }
        }
    }
}

/** Every species's portrait with its Tahitian name. */
@Composable
fun SpeciesGalleryPreviewContent() {
    BoxWithConstraints(Modifier.background(Ocean.Abyss).padding(12.dp)) {
        val perRow = if (maxWidth > 560.dp) 5 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SpeciesCatalog.all.chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { species ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SpeciesArt(species.id, Modifier.size(104.dp).clip(CircleShape))
                            Text(
                                species.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                color = Ocean.Foam,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(104.dp).padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One card per species, as in the picker. */
@Composable
fun SpeciesCardsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SpeciesCatalog.all.forEach { SpeciesCard(it, PreviewData.counts[it.id] ?: 0, enabled = true, onRelease = {}) }
        }
    }
}

// ------------------------------------------------------------------ previews in the IDE

@FormFactorPreviews
@Composable
private fun LagoonPreview() = TairotoTheme { LagoonScreenPreviewContent() }

@FormFactorPreviews
@Composable
private fun LagoonStatsPreview() = TairotoTheme { LagoonScreenPreviewContent(PreviewData.lagoonWithStats) }

@FormFactorPreviews
@Composable
private fun LagoonWithSpeciesPreview() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Species) }

@FormFactorPreviews
@Composable
private fun LagoonWithSettingsPreview() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Settings) }

@NarrowPreviews
@Composable
private fun SpeciesCardsPreview() = TairotoTheme { SpeciesCardsPreviewContent() }

@FormFactorPreviews
@Composable
private fun LagoonWithDecorPreview() = TairotoTheme { LagoonWithPanePreviewContent(PreviewPane.Decor) }

@FormFactorPreviews
@Composable
private fun LagoonEditingPreview() = TairotoTheme { LagoonEditingPreviewContent() }

@NarrowPreviews
@Composable
private fun LagoonNettingPreview() = TairotoTheme { LagoonEditingPreviewContent(PreviewData.editing(EditTool.NET, selected = false)) }

@NarrowPreviews
@Composable
private fun DecorCardsPreview() = TairotoTheme { DecorCardsPreviewContent() }

@androidx.compose.ui.tooling.preview.Preview(widthDp = 480, heightDp = 150)
@Composable
private fun DecorGalleryPreview() = TairotoTheme { DecorGalleryPreviewContent() }

@androidx.compose.ui.tooling.preview.Preview(widthDp = 600, heightDp = 300)
@androidx.compose.ui.tooling.preview.Preview(widthDp = 360, heightDp = 420)
@Composable
private fun SpeciesGalleryPreview() = TairotoTheme { SpeciesGalleryPreviewContent() }
