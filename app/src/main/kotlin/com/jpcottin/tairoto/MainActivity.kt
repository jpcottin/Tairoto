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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.jpcottin.tairoto.data.DataStoreSettings
import com.jpcottin.tairoto.data.FileLagoonStore
import com.jpcottin.tairoto.engine.NativeTank
import com.jpcottin.tairoto.ui.navigation.TairotoNavigation
import com.jpcottin.tairoto.ui.tank.DeviceSignals
import com.jpcottin.tairoto.ui.tank.TankViewModel
import com.jpcottin.tairoto.ui.theme.TairotoTheme

class MainActivity : ComponentActivity() {
    private lateinit var vm: TankViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val options = LaunchOptions.from(intent, debuggable)
        val factory = viewModelFactory {
            initializer {
                TankViewModel(
                    NativeTank(), DataStoreSettings(applicationContext), options, AndroidSignals(applicationContext),
                    lagoonStore = FileLagoonStore(applicationContext.filesDir),
                )
            }
        }
        vm = ViewModelProvider(this, factory)[TankViewModel::class.java]
        setContent {
            TairotoTheme {
                TairotoNavigation(vm)
            }
        }
    }

    /** Leaving the app (or turning the screen off): the lagoon is saved with everyone where they are. */
    override fun onStop() {
        super.onStop()
        vm.saveNow()
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
