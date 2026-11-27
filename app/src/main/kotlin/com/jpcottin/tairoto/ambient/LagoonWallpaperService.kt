package com.jpcottin.tairoto.ambient

import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import com.jpcottin.tairoto.data.FileLagoonStore
import com.jpcottin.tairoto.engine.NativeTank
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The lagoon as a live wallpaper: swiping the home screens pans across it, a tap ripples the water. */
class LagoonWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = LagoonEngine()

    inner class LagoonEngine : Engine() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private lateinit var player: LagoonPlayer
        private var downAt: Vec2? = null
        private var downTime = 0L

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            player = LagoonPlayer(
                NativeTank(), FileLagoonStore(applicationContext.filesDir), scope, drift = false,
                clockS = { SystemClock.uptimeMillis() / 1000.0 },
                daylight = { settingsDaylight(applicationContext) },
            )
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val m = resources.displayMetrics
            player.surface(holder.surface, width, height, m.xdpi, m.ydpi)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            player.surfaceGone()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) = player.visible(visible)

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPixels: Int, yPixels: Int) {
            // A launcher with one page says 0, or 0.5: either way, the middle of the lagoon.
            player.offset(if (xStep <= 0f || xStep >= 1f) 0.5f else xOffset, if (yStep <= 0f || yStep >= 1f) 0.5f else yOffset)
        }

        override fun onTouchEvent(event: MotionEvent) {
            // A tap, not a swipe between home screens.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = Vec2(event.x, event.y)
                    downTime = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val at = downAt ?: return
                    val slop = ViewConfiguration.get(applicationContext).scaledTouchSlop
                    val still = (Vec2(event.x, event.y) - at).length < slop
                    if (still && event.eventTime - downTime < ViewConfiguration.getTapTimeout() * 2) player.tap(at)
                    downAt = null
                }
                MotionEvent.ACTION_CANCEL -> downAt = null
            }
        }

        override fun onDestroy() {
            player.close()
            scope.cancel()
            super.onDestroy()
        }
    }
}
