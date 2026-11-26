package com.jpcottin.drifttank.ui.tank

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
import com.jpcottin.drifttank.engine.EngineStats
import com.jpcottin.drifttank.ui.theme.DriftTankTheme
import com.jpcottin.drifttank.ui.theme.GlassBorder
import com.jpcottin.drifttank.ui.theme.Ocean
import com.jpcottin.drifttank.ui.theme.StatsText
import com.jpcottin.drifttank.world.QualityTier
import com.jpcottin.drifttank.world.Vec2
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
}

@Composable
fun TankScreen(viewModel: TankViewModel, state: TankUiState, onOpenSettings: () -> Unit) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val vm by rememberUpdatedState(viewModel)
    val handler = remember {
        object : TankGestureHandler {
            override fun onTap(at: Vec2) = vm.tap(at)
            override fun onPan(delta: Vec2) = vm.pan(delta)
            override fun onZoom(focus: Vec2, factor: Float) = vm.zoom(focus, factor)
        }
    }
    val metrics = LocalContext.current.resources.displayMetrics

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
                .semantics { contentDescription = "The tank. Tap the water; pinch with two fingers to explore." }
                .tankGestures(handler),
        )
        TankHud(
            state = state,
            onAdd = { sheetOpen = true; vm.dismissHint() },
            onSettings = onOpenSettings,
        )
    }
    if (sheetOpen) {
        SpeciesSheet(
            counts = state.counts,
            roomLeft = state.roomLeft,
            onRelease = { vm.release(it) },
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
fun TankHud(state: TankUiState, onAdd: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { TitlePill(state.total) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassIconButton(onAdd, "Release creatures", TankTags.ADD) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(26.dp), tint = Ocean.Foam)
                    }
                    GlassIconButton(onSettings, "Settings", TankTags.SETTINGS) {
                        Icon(Icons.Outlined.Settings, null, Modifier.size(22.dp), tint = Ocean.Foam)
                    }
                }
            }
            // Below the bar, never beside it: a long GPU name must not push the buttons away.
            AnimatedVisibility(state.showStats, enter = fadeIn(), exit = fadeOut()) {
                StatsPanel(state.stats, state.tier, state.gpu, state.thermalHeadroom)
            }
        }
        ZoomChip(state.zoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
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
                    "Tap the water  ·  Pinch with two fingers to explore",
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
                    Text("This device cannot draw the tank", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Drift Tank needs Vulkan graphics, which this device does not offer.",
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
                    "Drift Tank",
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
fun StatsPanel(stats: EngineStats, tier: QualityTier, gpu: String, thermalHeadroom: Float? = null) {
    Surface(color = Ocean.GlassStrong, border = GlassBorder, shape = MaterialTheme.shapes.small, modifier = Modifier.widthIn(max = 420.dp).testTag(TankTags.STATS).semantics(mergeDescendants = true) {}) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(String.format(Locale.US, "%.0f fps  ·  %.1f ms", stats.fps, stats.workMs), style = StatsText)
            Text("${tier.label}  ·  ${stats.sceneWidth}×${stats.sceneHeight} → ${stats.width}×${stats.height}", style = StatsText)
            Text("${stats.drawn}/${stats.creatures} drawn  ·  ${stats.frames} frames", style = StatsText)
            Text(
                "thermal headroom " + (thermalHeadroom?.let { String.format(Locale.US, "%.2f", it) } ?: "n/a"),
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
    DriftTankTheme {
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
