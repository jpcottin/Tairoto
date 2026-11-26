package com.jpcottin.tairoto.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.SupportingPaneSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberSupportingPaneSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jpcottin.tairoto.BuildConfig
import com.jpcottin.tairoto.share.JoinTarget
import com.jpcottin.tairoto.share.ShareRoute
import com.jpcottin.tairoto.ui.settings.SettingsScreen
import com.jpcottin.tairoto.ui.share.ShareActions
import com.jpcottin.tairoto.ui.share.ShareSheet
import com.jpcottin.tairoto.ui.share.rememberLocalNetworkAccess
import com.jpcottin.tairoto.ui.settings.SettingsUi
import com.jpcottin.tairoto.ui.tank.SpeciesPicker
import com.jpcottin.tairoto.ui.tank.TankScreen
import com.jpcottin.tairoto.ui.tank.TankUiState
import com.jpcottin.tairoto.ui.tank.TankViewModel
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data object TankKey : NavKey

@Serializable
data object SpeciesKey : NavKey

@Serializable
data object SettingsKey : NavKey

/** The picker, open on its decorations. */
@Serializable
data object DecorKey : NavKey

/** Sharing the lagoon with other devices. */
@Serializable
data object ShareKey : NavKey

/**
 * How many panes fit side by side: two on a tablet, an unfolded foldable or a
 * wide desktop window; one on a phone or a folded cover screen. Never stacked
 * one above the other: on a tall narrow window a pane takes the whole screen.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun rememberLagoonDirective(): PaneScaffoldDirective {
    val info = currentWindowAdaptiveInfoV2()
    return remember(info) {
        calculatePaneScaffoldDirective(info).copy(maxVerticalPartitions = 1, horizontalPartitionSpacerSize = 0.dp)
    }
}

/**
 * The app's places. The lagoon is always the main pane. The species picker
 * and the settings are supporting panes: beside the lagoon on a wide window,
 * so it keeps swimming while you choose; on a narrow one the picker is a sheet
 * over it and the settings take the screen. Folding or unfolding moves an open
 * picker or settings between the two without losing it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun TairotoNavigation(vm: TankViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(TankKey)
    val directive = rememberLagoonDirective()
    val twoPane = directive.maxHorizontalPartitions > 1
    val supportingPane = rememberSupportingPaneSceneStrategy<NavKey>(directive = directive)
    val sheet = remember(twoPane) { BottomSheetSceneStrategy<NavKey>(enabled = !twoPane) }

    // One supporting pane at a time: the picker replaces the settings and back.
    fun open(key: NavKey) {
        if (backStack.lastOrNull() == key) return
        while (backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(key)
    }
    // Pops are idempotent: a double tap on close during the exit animation must not empty the stack.
    fun close(key: NavKey) {
        if (backStack.size > 1 && backStack.lastOrNull() == key) backStack.removeLastOrNull()
    }
    // Two panes side by side report no previous entries, which turns off
    // NavDisplay's own back handling; this one closes the supporting pane.
    BackHandler(enabled = backStack.size > 1) { backStack.removeLastOrNull() }
    // Back while arranging (with nothing open) finishes arranging.
    BackHandler(enabled = backStack.size == 1 && state.edit.active) { vm.setEditing(false) }

    // The lagoon is immersive unless the settings take the whole screen.
    Immersive(!(backStack.lastOrNull() == SettingsKey && !twoPane))

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
        sceneStrategies = listOf(sheet, supportingPane),
        entryProvider = entryProvider {
            entry<TankKey>(metadata = SupportingPaneSceneStrategy.mainPane()) {
                TankScreen(
                    viewModel = vm,
                    state = state,
                    onOpenSettings = { open(SettingsKey) },
                    onOpenSpecies = { vm.dismissHint(); open(SpeciesKey) },
                    onOpenDecor = { open(DecorKey) },
                    onOpenShare = { vm.dismissHint(); open(ShareKey) },
                )
            }
            entry<ShareKey>(metadata = SupportingPaneSceneStrategy.supportingPane() + BottomSheetSceneStrategy.bottomSheet()) {
                val localNetwork = rememberLocalNetworkAccess(
                    onGranted = { vm.setLocalNetworkAllowed(true) },
                    onRefused = vm::localNetworkRefused,
                )
                // Looks for lagoons nearby while the sheet is open (and the network may be reached).
                DisposableEffect(localNetwork.granted) {
                    vm.setLocalNetworkAllowed(localNetwork.granted)
                    if (localNetwork.granted) vm.lookForLagoons(true)
                    onDispose { vm.lookForLagoons(false) }
                }
                val actions = remember(vm, localNetwork) {
                    object : ShareActions {
                        override fun share(route: ShareRoute) = localNetwork.then { vm.shareLagoon(route) }
                        override fun join(target: JoinTarget) = localNetwork.then { vm.joinLagoon(target) }
                        override fun stop() = vm.stopSharing()
                        override fun relayChanged(text: String) = vm.setRelayAddress(text)
                        override fun dismissMessage() = vm.dismissShareMessage()
                        override fun allowLocalNetwork() = localNetwork.then { vm.lookForLagoons(true) }
                    }
                }
                ShareSheet(state.share, actions, onClose = if (twoPane) ({ close(ShareKey) }) else null)
            }
            entry<DecorKey>(metadata = SupportingPaneSceneStrategy.supportingPane() + BottomSheetSceneStrategy.bottomSheet()) {
                Picker(vm, state, twoPane, startOnDecor = true, close = { close(DecorKey) })
            }
            entry<SpeciesKey>(metadata = SupportingPaneSceneStrategy.supportingPane() + BottomSheetSceneStrategy.bottomSheet()) {
                Picker(vm, state, twoPane, startOnDecor = false, close = { close(SpeciesKey) })
            }
            entry<SettingsKey>(metadata = SupportingPaneSceneStrategy.supportingPane()) {
                SettingsScreen(
                    ui = SettingsUi(
                        quality = state.qualityMode,
                        activeTier = state.tier,
                        showStats = state.showStats,
                        creatures = state.total,
                        gpu = state.gpu,
                        version = BuildConfig.VERSION_NAME,
                        tankMm = state.tankSize?.let { String.format(Locale.US, "%.0f × %.0f mm", it.widthMm, it.heightMm) } ?: "",
                    ),
                    onBack = { close(SettingsKey) },
                    onQuality = vm::setQualityMode,
                    onShowStats = vm::setShowStats,
                    onResetTank = vm::resetTank,
                    inPane = twoPane,
                )
            }
        },
    )
}

/**
 * What can go into the lagoon. Placing a decoration from a sheet closes it,
 * so the decoration can be seen and dragged into place; a pane beside the
 * lagoon stays open.
 */
@Composable
private fun Picker(vm: TankViewModel, state: TankUiState, twoPane: Boolean, startOnDecor: Boolean, close: () -> Unit) {
    SpeciesPicker(
        counts = state.counts,
        roomLeft = state.roomLeft,
        onRelease = vm::release,
        onClose = if (twoPane) close else null,
        decorCounts = state.decorCounts,
        decorRoomLeft = state.decorRoomLeft,
        onPlace = { id ->
            vm.placeDecor(id)
            if (!twoPane) close()
        },
        startOnDecor = startOnDecor,
    )
}

/** The lagoon fills the screen with the system bars hidden (they come back with a swipe). */
@Composable
private fun Immersive(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        val window = (view.context as? ComponentActivity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}
