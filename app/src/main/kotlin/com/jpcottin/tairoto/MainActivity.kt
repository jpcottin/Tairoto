package com.jpcottin.tairoto

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jpcottin.tairoto.data.DataStoreSettings
import com.jpcottin.tairoto.engine.NativeTank
import com.jpcottin.tairoto.ui.settings.SettingsScreen
import com.jpcottin.tairoto.ui.settings.SettingsUi
import com.jpcottin.tairoto.ui.tank.DeviceSignals
import com.jpcottin.tairoto.ui.tank.TankScreen
import com.jpcottin.tairoto.ui.tank.TankViewModel
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data object TankKey : NavKey

@Serializable
data object SettingsKey : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val options = LaunchOptions.from(intent, debuggable)
        setContent {
            TairotoTheme {
                val vm: TankViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            TankViewModel(NativeTank(), DataStoreSettings(applicationContext), options, AndroidSignals(applicationContext))
                        }
                    },
                )
                TairotoApp(vm)
            }
        }
    }
}

@Composable
fun TairotoApp(vm: TankViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(TankKey)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<TankKey> {
                Immersive(true)
                TankScreen(vm, state, onOpenSettings = { backStack.add(SettingsKey) })
            }
            entry<SettingsKey> {
                Immersive(false)
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
                    onBack = { backStack.removeLastOrNull() },
                    onQuality = vm::setQualityMode,
                    onShowStats = vm::setShowStats,
                    onResetTank = vm::resetTank,
                )
            }
        },
    )
}

/** The tank fills the screen with the system bars hidden (they come back with a swipe). */
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

private class AndroidSignals(private val context: Context) : DeviceSignals {
    private val power = context.getSystemService(PowerManager::class.java)
    override fun thermalHeadroom(): Float? = power?.getThermalHeadroom(10)?.takeIf { !it.isNaN() }
    private val displays = context.getSystemService(DisplayManager::class.java)
    override fun refreshRateHz(): Float = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
    override fun nowMs(): Long = SystemClock.uptimeMillis()
    override val thermalIsSimulated: Boolean = isEmulator()

    private fun isEmulator(): Boolean =
        Build.HARDWARE in setOf("ranchu", "goldfish", "cutf_cvm") ||
            Build.PRODUCT.startsWith("sdk_") || (Build.FINGERPRINT.contains("generic") && Build.MODEL.contains("sdk"))
}
