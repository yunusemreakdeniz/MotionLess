package io.github.yunusemreakdeniz.motionless

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile. Each tap moves to the next mode: Off -> Auto -> On -> Off. */
class CueTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update(Prefs.mode(this))
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val current = Prefs.mode(this)
        val next = when (current) {
            CueMode.OFF -> CueMode.AUTO
            CueMode.AUTO -> CueMode.ON
            CueMode.ON -> CueMode.OFF
        }
        if (current != CueMode.OFF) {
            MotionCueService.switchTo(this, next)
            update(next)
            return
        }
        // Starting a foreground service straight from a tile is blocked on newer Android versions,
        // so the activity starts it and goes back to the background.
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START)
            .putExtra(MainActivity.EXTRA_MODE, next.name)
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

    private fun update(mode: CueMode) {
        qsTile?.apply {
            state = if (mode == CueMode.OFF) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = getString(
                    when (mode) {
                        CueMode.OFF -> R.string.mode_off
                        CueMode.AUTO -> R.string.mode_auto
                        CueMode.ON -> R.string.mode_on
                    }
                )
            }
            updateTile()
        }
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, CueTileService::class.java))
        }
    }
}
