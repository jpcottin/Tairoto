package com.jpcottin.tairoto.ambient

import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.jpcottin.tairoto.data.FileLagoonStore
import com.jpcottin.tairoto.engine.NativeTank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The lagoon as a screensaver, for a tablet charging on its stand: full screen, roaming slowly. */
class LagoonDreamService : DreamService() {
    private var scope: CoroutineScope? = null
    private var player: LagoonPlayer? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        val p = LagoonPlayer(
            NativeTank(), FileLagoonStore(applicationContext.filesDir), s, drift = true,
            clockS = { SystemClock.uptimeMillis() / 1000.0 },
                daylight = { settingsDaylight(applicationContext) },
        )
        player = p
        val view = SurfaceView(this)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val m = resources.displayMetrics
                p.surface(holder.surface, width, height, m.xdpi, m.ydpi)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = p.surfaceGone()
        })
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        player?.visible(true)
    }

    override fun onDreamingStopped() {
        player?.visible(false)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        player?.close()
        player = null
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }
}
