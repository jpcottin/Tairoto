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
        Build.VERSION.SDK_INT < SINCE_SDK ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/** Runs actions that need the local network, asking for it first when needed. */
@Stable
class LocalNetworkAccess internal constructor(
    private val context: Context,
    private val ask: (() -> Unit) -> Unit,
) {
    var granted by mutableStateOf(LocalNetwork.granted(context))
        internal set

    /** Runs [action] now if the local network may be reached, or once the person allows it. */
    fun then(action: () -> Unit) {
        if (LocalNetwork.granted(context)) {
            granted = true
            action()
        } else {
            ask(action)
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        access.granted = ok
        if (ok) {
            granted()
            pending?.invoke()
        } else {
            refused()
        }
        pending = null
    }
    access = remember(context) {
        LocalNetworkAccess(context) { action ->
            pending = action
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
    return access
}
