package com.jpcottin.tairoto.ui.share

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Reaching other devices on the local network (or the computer emulators run
 * on) is a runtime permission from Android 17 for apps that target it. Before
 * that, the INTERNET permission is enough and nothing is asked.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    const val SINCE_SDK = 37

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < SINCE_SDK || has(context, PERMISSION)

    internal fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Finding and reaching lagoons over Bluetooth: scanning (never to locate
 * anyone), advertising a lagoon, and connecting. Runtime permissions from
 * Android 12; before that the install-time ones are enough.
 */
object BluetoothAccess {
    const val SINCE_SDK = 31
    val PERMISSIONS = listOf(
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.BLUETOOTH_CONNECT",
    )

    fun granted(context: Context): Boolean =
        Build.VERSION.SDK_INT < SINCE_SDK || PERMISSIONS.all { LocalNetwork.has(context, it) }
}

/**
 * Finding and making Wi-Fi Direct groups (never to locate anyone): a runtime
 * permission from Android 13, where Wi-Fi Direct lagoons start.
 */
object WifiDirectAccess {
    const val SINCE_SDK = 33
    const val PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"

    fun granted(context: Context): Boolean = Build.VERSION.SDK_INT < SINCE_SDK || LocalNetwork.has(context, PERMISSION)
}

/**
 * Runs actions that need to reach devices nearby, asking first for what is
 * missing: the local network (needed), and Bluetooth (welcome: without it a
 * lagoon is shared over Wi-Fi only), and Wi-Fi Direct (welcome too).
 */
@Stable
class LocalNetworkAccess internal constructor(
    private val context: Context,
    private val ask: (List<String>, () -> Unit) -> Unit,
) {
    var granted by mutableStateOf(LocalNetwork.granted(context))
        internal set
    var bluetooth by mutableStateOf(BluetoothAccess.granted(context))
        internal set
    private var asked = false

    /** What would be asked for now. */
    internal fun missing(): List<String> = buildList {
        if (!LocalNetwork.granted(context)) add(LocalNetwork.PERMISSION)
        if (!BluetoothAccess.granted(context)) addAll(BluetoothAccess.PERMISSIONS)
        if (!WifiDirectAccess.granted(context)) add(WifiDirectAccess.PERMISSION)
    }

    /** Runs [action] now if the local network may be reached, or once the person allows it. */
    fun then(action: () -> Unit) {
        val missing = missing()
        // Bluetooth refused once is not asked again each time: Wi-Fi works without it.
        if (LocalNetwork.granted(context) && (missing.isEmpty() || asked)) {
            granted = true
            action()
        } else {
            asked = true
            ask(missing, action)
        }
    }
}

@Composable
fun rememberLocalNetworkAccess(onGranted: () -> Unit, onRefused: () -> Unit): LocalNetworkAccess {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    val refused by rememberUpdatedState(onRefused)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    lateinit var access: LocalNetworkAccess
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val ok = LocalNetwork.granted(context)
        access.granted = ok
        access.bluetooth = BluetoothAccess.granted(context)
        if (ok) {
            granted()
            pending?.invoke()
        } else {
            refused()
        }
        pending = null
    }
    access = remember(context) {
        LocalNetworkAccess(context) { missing, action ->
            pending = action
            launcher.launch(missing.toTypedArray())
        }
    }
    return access
}
