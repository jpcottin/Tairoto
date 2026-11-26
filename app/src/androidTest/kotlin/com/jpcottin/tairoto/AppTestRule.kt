package com.jpcottin.tairoto

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jpcottin.tairoto.data.DataStoreSettings
import com.jpcottin.tairoto.world.QualityMode
import kotlinx.coroutines.runBlocking

/** Launches the app with test extras on clean settings. */
object AppLauncher {
    fun resetSettings() = runBlocking {
        val store = DataStoreSettings(ApplicationProvider.getApplicationContext())
        store.setQuality(QualityMode.Auto)
        store.setShowStats(false)
    }

    fun launch(
        ci: Boolean = true,
        stats: Boolean? = null,
        seed: Long = 7,
        fresh: Boolean = true,
        extras: Intent.() -> Unit = {},
    ): ActivityScenario<MainActivity> {
        resetSettings()
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
            putExtra(LaunchOptions.SEED, seed)
            putExtra(LaunchOptions.HINTS, false)
            if (fresh) putExtra(LaunchOptions.FRESH, true)
            if (ci) putExtra(LaunchOptions.CI, true)
            if (stats != null) putExtra(LaunchOptions.STATS, stats)
            extras()
        }
        return ActivityScenario.launch(intent)
    }
}

fun SemanticsNodeInteraction.text(): String =
    fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: ""

fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 25_000) =
    waitUntil(timeoutMs) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

fun ComposeTestRule.waitForText(tag: String, timeoutMs: Long = 25_000, predicate: (String) -> Boolean) =
    waitUntil(timeoutMs) {
        val nodes = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        nodes.any { n -> predicate(n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "") }
    }
