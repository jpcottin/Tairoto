package com.jpcottin.tairoto.ui.tank

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.width
import com.jpcottin.tairoto.ui.share.ArrangeOverlay
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.geometry.Rect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jpcottin.tairoto.engine.EngineStats
import com.jpcottin.tairoto.ui.share.SharePill
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.ui.theme.GlassBorder
import com.jpcottin.tairoto.ui.theme.Ocean
import com.jpcottin.tairoto.ui.theme.StatsText
import com.jpcottin.tairoto.world.QualityTier
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.delay
import java.util.Locale

object TankTags {
    const val SURFACE = "tank_surface"
    const val GESTURES = "tank_gestures"
    const val TITLE = "tank_title"
    const val COUNT = "tank_count"
    const val ADD = "tank_add"
    const val SETTINGS = "tank_settings"
    const val HINT = "tank_hint"
    const val STATS = "tank_stats"
    const val ZOOM = "tank_zoom"
    const val UNSUPPORTED = "tank_unsupported"
    const val ARRANGE_HINT = "tank_arrange_hint"
}

@Composable
fun TankScreen(
    viewModel: TankViewModel,
    state: TankUiState,
    onOpenSettings: () -> Unit,
    onOpenSpecies: () -> Unit,
    onOpenDecor: () -> Unit = {},
    onOpenShare: () -> Unit = {},
) {
    val vm by rememberUpdatedState(viewModel)
    val handler = remember {
        object : TankGestureHandler {
            override fun onTap(at: Vec2) = vm.tap(at)
            override fun onPan(delta: Vec2) = vm.pan(delta)
            override fun onZoom(focus: Vec2, factor: Float) = vm.zoom(focus, factor)
            override fun onSprinkle(at: Vec2, first: Boolean) = vm.sprinkle(at, first)
            override fun onSprinkleEnd() = vm.endSprinkle()
            override fun onLongPress(at: Vec2) = vm.dropPellet(at)
            override fun onDragStart(at: Vec2): Boolean = vm.beginDrag(at)
            override fun onDrag(at: Vec2) = vm.drag(at)
            override fun onRotate(radians: Float) = vm.rotateSelected(radians)
            override fun onGestureEnd() = vm.finishGesture()
            override fun onStrokeStart(start: Vec2, toward: Vec2) = vm.strokeStart(start, toward)
            override fun onDown() = vm.touched()
            override fun onStrokeEnd(from: Vec2, end: Vec2) = vm.strokeEnd(from, end)
        }
    }
    val actions = remember {
        object : EditActions {
            override fun done() = vm.setEditing(false)
            override fun undo() = vm.undo()
            override fun redo() = vm.redo()
            override fun tool(tool: EditTool) = vm.setTool(tool)
            override fun add() = onOpenDecor()
            override fun turn() = vm.turnSelected()
            override fun remove() = vm.removeSelected()
        }
    }
    val metrics = LocalContext.current.resources.displayMetrics
    val edgeBandPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    val edgeTallPx = with(androidx.compose.ui.platform.LocalDensity.current) { 200.dp.toPx() }

    Box(Modifier.fillMaxSize().background(Ocean.Deep)) {
        AndroidExternalSurface(Modifier.fillMaxSize().testTag(TankTags.SURFACE)) {
            onSurface { surface, width, height ->
                vm.onSurface(surface, width, height, metrics.xdpi, metrics.ydpi)
                surface.onChanged { w, h -> vm.onSurfaceSize(w, h, metrics.xdpi, metrics.ydpi) }
                surface.onDestroyed { vm.onSurfaceGone() }
            }
        }
        // Gestures on a layer of their own: the SurfaceView underneath keeps its touches otherwise.
        Box(
            Modifier
                .fillMaxSize()
                .testTag(TankTags.GESTURES)
                .semantics {
                    contentDescription = if (state.edit.active) {
                        "The lagoon, being arranged. Tap a decoration to select it, drag it to move it; with the net, tap a creature."
                    } else {
                        "The lagoon. Tap the water; drag a finger to sprinkle food; hold for a pellet; pinch with two fingers to explore."
                    }
                }
                .then(if (state.share.arranging) Modifier.swipeEdgesForTheApp(edgeBandPx, edgeTallPx) else Modifier)
                .tankGestures(handler),
        )
        SelectionRing(state.edit.selection)
        NetSweepEffect(state.edit.sweep)
        if (state.share.arranging && state.share.phase == SharePhase.SHARED) {
            ArrangeOverlay(state.share, onDone = { vm.setArranging(false) })
        } else if (state.edit.active) {
            EditOverlay(
                state.edit, actions,
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp),
            )
        } else {
            // Shared and left alone, the buttons fade: the picture runs on across the screens without them.
            val hud by androidx.compose.animation.core.animateFloatAsState(if (state.hudHidden) 0f else 1f, androidx.compose.animation.core.tween(700), label = "hud")
            if (hud > 0f) TankHud(
                modifier = Modifier.graphicsLayer { alpha = hud },
                state = state,
                onAdd = onOpenSpecies,
                onSettings = onOpenSettings,
                onArrange = { vm.setEditing(true) },
                onShare = onOpenShare,
                onArrangeScreens = {
                    vm.dismissArrangeHint()
                    vm.setArranging(true)
                },
            )
        }
    }
}

/**
 * While the screens are arranged, a finger coming on over the left or right
 * edge is the app's, not the system's back gesture (which gives an app up to
 * 200 dp of each side, so: the middle of each side).
 */
private fun Modifier.swipeEdgesForTheApp(bandPx: Float, tallPx: Float): Modifier {
    fun side(left: Boolean) = { coords: androidx.compose.ui.layout.LayoutCoordinates ->
        val w = coords.size.width.toFloat()
        val h = coords.size.height.toFloat()
        val tall = minOf(h, tallPx)
        val top = (h - tall) / 2f
        if (left) Rect(0f, top, bandPx, top + tall) else Rect(w - bandPx, top, w, top + tall)
    }
    return systemGestureExclusion(side(true)).systemGestureExclusion(side(false))
}

@Composable
fun TankHud(
    state: TankUiState,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onArrange: () -> Unit = {},
    onShare: () -> Unit = {},
    onArrangeScreens: () -> Unit = {},
) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { TitlePill(state.total) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(onArrange, "Arrange the lagoon", EditTags.ARRANGE) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(22.dp), tint = Ocean.Foam)
                    }
                    GlassIconButton(onAdd, "Release creatures", TankTags.ADD) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(26.dp), tint = Ocean.Foam)
                    }
                    GlassIconButton(onSettings, "Settings", TankTags.SETTINGS) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(22.dp), tint = Ocean.Foam)
                    }
                }
            }
            // Below the bar, never beside it: a long GPU name must not push the buttons away.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(state.showStats, enter = fadeIn(), exit = fadeOut()) {
                        StatsPanel(state.stats, state.tier, state.gpu, state.thermalHeadroom, state.thermalIgnored)
                    }
                }
                SharePill(state.share, onShare, Modifier.padding(start = 8.dp))
            }
        }
        ZoomChip(state.zoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        AnimatedVisibility(
            visible = state.share.arrangeHint,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                onClick = onArrangeScreens,
                color = Ocean.GlassStrong,
                border = GlassBorder,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag(TankTags.ARRANGE_HINT),
            ) {
                Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.share.devices.size} screens: line them up?",
                        style = MaterialTheme.typography.labelLarge,
                        color = Ocean.Foam,
                    )
                    Spacer(Modifier.width(10.dp))
                    Surface(color = Ocean.SeaGlass, shape = RoundedCornerShape(50)) {
                        Text("Arrange", style = MaterialTheme.typography.labelLarge, color = Ocean.Deep, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.hintVisible && state.ready,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = Ocean.Glass,
                border = GlassBorder,
                shape = RoundedCornerShape(50),
                modifier = Modifier.testTag(TankTags.HINT),
            ) {
                Text(
                    "Drag to sprinkle food  ·  Hold for a pellet  ·  Pinch to explore",
                    style = MaterialTheme.typography.labelLarge,
                    color = Ocean.Foam,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        if (state.vulkanUnsupported) {
            Surface(
                color = Ocean.GlassStrong,
                border = GlassBorder,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp).testTag(TankTags.UNSUPPORTED),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This device cannot draw the lagoon", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tairoto needs Vulkan graphics, which this device does not offer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitlePill(total: Int) {
    Surface(color = Ocean.Glass, border = GlassBorder, shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(start = 16.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).background(Ocean.SeaGlass, CircleShape))
            Column {
                Text(
                    "Tairoto",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Ocean.Foam,
                    modifier = Modifier.testTag(TankTags.TITLE),
                )
                Text(
                    if (total == 1) "1 creature" else "$total creatures",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ocean.Mist,
                    modifier = Modifier.testTag(TankTags.COUNT),
                )
            }
        }
    }
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, label: String, tag: String, icon: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        color = Ocean.Glass,
        border = GlassBorder,
        shape = CircleShape,
        modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
fun StatsPanel(stats: EngineStats, tier: QualityTier, gpu: String, thermalHeadroom: Float? = null, thermalIgnored: Boolean = false) {
    Surface(color = Ocean.GlassStrong, border = GlassBorder, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 420.dp).testTag(TankTags.STATS).semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(String.format(Locale.US, "%.0f fps  ·  %.1f ms", stats.fps, stats.workMs), style = StatsText)
            Text("${tier.label}  ·  ${stats.sceneWidth}×${stats.sceneHeight} → ${stats.width}×${stats.height}", style = StatsText)
            Text("${stats.drawn}/${stats.creatures} drawn  ·  ${stats.frames} frames", style = StatsText)
            Text(
                String.format(Locale.US, "food %d  ·  %.1f mg  ·  eaten %.1f mg  ·  hunger %.2f", stats.food, stats.foodMg, stats.eatenMg, stats.hunger),
                style = StatsText,
            )
            if (stats.audio != com.jpcottin.tairoto.engine.AudioState.OFF) {
                Text(
                    if (stats.audio == com.jpcottin.tairoto.engine.AudioState.PLAYING) "sound ${stats.audioRateHz} Hz" else "sound: no output",
                    style = StatsText,
                )
            }
            Text(
                "thermal headroom " + (thermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a") +
                    if (thermalIgnored) "  (emulator: ignored)" else "",
                style = StatsText,
            )
            if (gpu.isNotEmpty()) Text(gpu, style = StatsText.copy(color = Ocean.Mist), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shows the zoom for a moment after it changes. */
@Composable
private fun ZoomChip(zoom: Float, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(zoom) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        visible = true
        delay(1_100)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(color = Ocean.Glass, border = GlassBorder, shape = RoundedCornerShape(50)) {
            Text(
                String.format(Locale.US, "%.1f×", zoom),
                style = MaterialTheme.typography.labelLarge,
                color = Ocean.Foam,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).testTag(TankTags.ZOOM),
            )
        }
    }
}

@Preview(widthDp = 400, heightDp = 860)
@Composable
private fun TankHudPreview() {
    TairotoTheme {
        Box(Modifier.fillMaxSize().background(Ocean.Teal)) {
            TankHud(
                state = TankUiState(ready = true, total = 23, showStats = true, hintVisible = true,
                    stats = EngineStats(fps = 60f, workMs = 2.4f, creatures = 23, drawn = 19, frames = 1840,
                        sceneWidth = 918, sceneHeight = 2040, width = 1080, height = 2400),
                    gpu = "Adreno (TM) 750"),
                onAdd = {}, onSettings = {},
            )
        }
    }
}
