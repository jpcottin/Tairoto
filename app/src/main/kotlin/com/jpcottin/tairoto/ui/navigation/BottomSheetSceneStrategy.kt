package com.jpcottin.tairoto.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * Shows an entry marked with [bottomSheet] as a modal bottom sheet over the
 * entries below it, so the lagoon keeps swimming behind it. Only while
 * [enabled]: on a wide window the same entry becomes a supporting pane, and a
 * sheet open when a foldable is unfolded turns into that pane (and back).
 */
class BottomSheetSceneStrategy<T : Any>(private val enabled: Boolean = true) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!enabled) return null
        val last = entries.lastOrNull() ?: return null
        if (last.metadata[KEY] != true) return null
        val below = entries.dropLast(1)
        return BottomSheetScene(last.contentKey, last, below, below, onBack)
    }

    companion object {
        private const val KEY = "tairoto.bottom_sheet"
        const val TAG = "bottom_sheet_scene"

        /** Metadata for an entry that is a bottom sheet on a narrow window. */
        fun bottomSheet(): Map<String, Any> = mapOf(KEY to true)
    }
}

private class BottomSheetScene<T : Any>(
    override val key: Any,
    private val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val onBack: () -> Unit,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    @OptIn(ExperimentalMaterial3Api::class)
    override val content: @Composable () -> Unit = {
        ModalBottomSheet(
            onDismissRequest = onBack,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.testTag(BottomSheetSceneStrategy.TAG),
        ) {
            entry.Content()
        }
    }

    override fun equals(other: Any?): Boolean = other is BottomSheetScene<*> && other.key == key && other.entry == entry
    override fun hashCode(): Int = key.hashCode() * 31 + entry.hashCode()
}
