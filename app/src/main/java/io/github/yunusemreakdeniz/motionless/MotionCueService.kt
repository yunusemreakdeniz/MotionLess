package io.github.yunusemreakdeniz.motionless

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps the dot overlay on screen above every app while the cues are on. */
class MotionCueService : Service() {

    private var overlay: MotionCueView? = null
    private var engine: MotionEngine? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.KEY_RUNNING) overlay?.settings = Prefs.load(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (overlay == null) attachOverlay()
        Prefs.setRunning(this, true)
        CueTileService.refresh(this)
        return START_STICKY
    }

    private fun attachOverlay() {
        val windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = MotionCueView(this).apply { settings = Prefs.load(this@MotionCueService) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Android 12+ only lets touches pass through another app's window at 80% opacity or less.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windows.addView(view, params)
        overlay = view
        engine = MotionEngine(this) { lateral, forward -> view.onMotion(lateral, forward) }.also { it.start() }
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        overlay?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Prefs.setRunning(this, false)
        CueTileService.refresh(this)
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MotionCueService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dots)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL_ID = "cues"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.yunusemreakdeniz.motionless.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MotionCueService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MotionCueService::class.java))
        }
    }
}
