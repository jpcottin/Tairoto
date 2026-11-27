package com.jpcottin.tairoto.ambient

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.jpcottin.tairoto.ui.settings.AmbientActions

/** Opens the system's own screens to set the lagoon as the wallpaper, or to pick it as the screensaver. */
class AmbientIntents(private val context: Context) : AmbientActions {
    override fun setWallpaper() {
        val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, LagoonWallpaperService::class.java))
        // Some devices have no live wallpaper preview: then the list of live wallpapers.
        open(preview) || open(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }

    override fun openScreensaver() {
        open(Intent(Settings.ACTION_DREAM_SETTINGS)) || open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
    }

    private fun open(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("Tairoto", "nothing opens ${intent.action}")
        false
    }
}
