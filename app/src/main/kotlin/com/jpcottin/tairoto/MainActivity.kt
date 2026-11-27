package com.jpcottin.tairoto

import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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
import com.jpcottin.tairoto.share.AndroidShareConnector
import com.jpcottin.tairoto.share.NetLogFile
import com.jpcottin.tairoto.ui.navigation.TairotoNavigation
import com.jpcottin.tairoto.ui.tank.DeviceSignals
import com.jpcottin.tairoto.ui.tank.TankViewModel
import com.jpcottin.tairoto.ui.theme.TairotoTheme
import com.jpcottin.tairoto.world.ScreenMm

/** One JSON line per handoff, loss and status of a shared lagoon (scripts/share-e2e.sh reads them). */
const val NET_TAG = "TairotoNet"

class MainActivity : ComponentActivity() {
    private lateinit var vm: TankViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val options = LaunchOptions.from(intent, debuggable)
        // In a debug build every line also goes to a file: logcat drops lines from a busy app.
        val logFile = if (debuggable) NetLogFile(filesDir) else null
        val factory = viewModelFactory {
            initializer {
                TankViewModel(
                    NativeTank(), DataStoreSettings(applicationContext), options, AndroidSignals(applicationContext),
                    lagoonStore = FileLagoonStore(applicationContext.filesDir),
                    connector = AndroidShareConnector(applicationContext),
                    deviceName = deviceName(),
                    bezelMm = bezelMm(),
                    netLog = { line ->
                        Log.i(NET_TAG, line)
                        logFile?.append(line)
                    },
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

    /** What the others see this device as: the name its owner gave it, or its model. */
    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * The frame around the screen. An emulator's window has none: windows set
     * side by side on a computer join with no gap.
     */
    private fun bezelMm(): Float = if (isEmulator()) 0f else ScreenMm.PHONE_BEZEL_MM

    private fun isEmulator(): Boolean =
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_") || Build.FINGERPRINT.startsWith("generic")

    /**
     * Leaving the app (or turning the screen off): the lagoon is saved with
     * everyone where they are, and a shared lagoon is left (nothing swims
     * here while the app is not on screen).
     */
    override fun onStop() {
        super.onStop()
        vm.saveNow()
        vm.onBackground()
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
