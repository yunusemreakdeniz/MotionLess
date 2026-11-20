package io.github.yunusemreakdeniz.motionless

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile that turns the cues on and off. */
class CueTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update(Prefs.isRunning(this))
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Prefs.isRunning(this)) {
            MotionCueService.stop(this)
            update(false)
            return
        }
        // Starting a foreground service straight from a tile is blocked on newer Android versions,
        // so the activity starts it and goes back to the background.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(running: Boolean) {
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, CueTileService::class.java))
        }
    }
}
