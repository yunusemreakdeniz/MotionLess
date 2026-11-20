package io.github.yunusemreakdeniz.motionless

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the cues. In ON mode the dot overlay is always shown. In AUTO mode GPS runs while the screen
 * is on, and the overlay appears once the speed passes the threshold; it goes away after the speed
 * stays below half the threshold for a minute, so short stops at lights do not make it flicker.
 * The ongoing notification switches between the modes.
 */
class MotionCueService : Service() {

    private var mode = CueMode.OFF
    private var overlay: MotionCueView? = null
    private var engine: MotionEngine? = null

    private var screenOn = true
    private var receiverRegistered = false
    private var gpsOn = false
    private var speedKmh: Int? = null
    private var lastLocation: Location? = null
    private var autoActive = false
    private var slowSince = 0L
    private var lastNotificationKey = ""

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.KEY_MODE && key != Prefs.KEY_STATUS) overlay?.settings = Prefs.load(this)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            if (mode == CueMode.AUTO) updateAuto()
        }
    }

    // All four callbacks are overridden: before Android 11 they are abstract and the system calls them.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = updateNotification(force = true)
        override fun onProviderDisabled(provider: String) {
            speedKmh = null
            updateNotification(force = true)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { CueMode.valueOf(it) }.getOrNull() }
            ?: Prefs.mode(this).takeIf { it != CueMode.OFF }
            ?: CueMode.ON
        startInForeground(buildNotification(requested))
        if (requested == CueMode.OFF || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        mode = requested
        Prefs.setMode(this, mode)
        when (mode) {
            CueMode.ON -> {
                stopGps()
                showOverlay(true)
            }
            CueMode.AUTO -> {
                if (!receiverRegistered) {
                    registerReceiver(screenReceiver, IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    })
                    receiverRegistered = true
                }
                autoActive = false
                slowSince = 0L
                updateAuto()
            }
            CueMode.OFF -> Unit
        }
        updateNotification(force = true)
        CueTileService.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        stopGps()
        showOverlay(false)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Prefs.setMode(this, CueMode.OFF)
        Prefs.setStatus(this, "")
        CueTileService.refresh(this)
        super.onDestroy()
    }

    // ---- Auto mode ----

    private fun updateAuto() {
        if (!screenOn) {
            stopGps()
            autoActive = false
            slowSince = 0L
            showOverlay(false)
        } else {
            startGps()
            showOverlay(autoActive)
        }
        updateNotification()
    }

    private fun onLocation(location: Location) {
        val previous = lastLocation
        lastLocation = location
        val metersPerSecond = when {
            location.hasSpeed() -> location.speed
            previous != null && location.time > previous.time ->
                location.distanceTo(previous) / ((location.time - previous.time) / 1000f)
            else -> return
        }
        val kmh = metersPerSecond * 3.6f
        speedKmh = kmh.roundToInt()

        val threshold = Prefs.load(this).speedThresholdKmh
        val now = SystemClock.elapsedRealtime()
        when {
            kmh >= threshold -> {
                autoActive = true
                slowSince = 0L
            }
            kmh < max(5f, threshold * 0.5f) -> {
                if (slowSince == 0L) slowSince = now
                if (now - slowSince > STOP_AFTER_MS) autoActive = false
            }
            else -> slowSince = 0L
        }
        if (mode == CueMode.AUTO) showOverlay(autoActive && screenOn)
        updateNotification()
    }

    private fun locationEnabled(): Boolean =
        LocationManagerCompat.isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startGps() {
        if (gpsOn || !hasLocationPermission()) return
        val locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
            gpsOn = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        gpsOn = false
        speedKmh = null
        lastLocation = null
    }

    // ---- Overlay ----

    private fun showOverlay(show: Boolean) {
        val windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (show && overlay == null) {
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
        } else if (!show && overlay != null) {
            engine?.stop()
            engine = null
            overlay?.let { windows.removeView(it) }
            overlay = null
        }
    }

    // ---- Notification ----

    private fun startInForeground(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        manager.createNotificationChannel(
            // Default importance keeps the notification expanded with its buttons; it stays silent.
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (_: SecurityException) {
            // Location type is refused when the app is in the background; keep running without it.
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
        }
    }

    private fun updateNotification(force: Boolean = false) {
        if (mode == CueMode.OFF) return
        val key = "$mode|$autoActive|$screenOn|$gpsOn|$speedKmh|${locationEnabled()}"
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key
        val notification = buildNotification(mode)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(forMode: CueMode): Notification {
        val threshold = Prefs.load(this).speedThresholdKmh
        val title: String
        val text: String
        if (forMode == CueMode.AUTO) {
            title = getString(if (autoActive && screenOn) R.string.notif_auto_active else R.string.notif_auto_waiting)
            text = when {
                !hasLocationPermission() -> getString(R.string.notif_auto_no_permission)
                !locationEnabled() -> getString(R.string.notif_auto_location_off)
                !screenOn -> getString(R.string.notif_auto_screen_off)
                speedKmh != null -> getString(R.string.notif_auto_speed, speedKmh, threshold)
                else -> getString(R.string.notif_auto_searching, threshold)
            }
        } else {
            title = getString(R.string.notif_on)
            text = getString(R.string.notif_text)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dots)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // All three modes are always offered; the current one is marked.
        for ((target, label) in listOf(
            CueMode.OFF to R.string.action_off,
            CueMode.AUTO to R.string.action_auto,
            CueMode.ON to R.string.action_on,
        )) {
            val name = getString(label)
            builder.addAction(0, if (target == forMode) "✓ $name" else name, modeIntent(target))
        }
        Prefs.setStatus(this, "$title · $text")
        return builder.build()
    }

    private fun modeIntent(target: CueMode): PendingIntent = PendingIntent.getService(
        this, 10 + target.ordinal,
        Intent(this, MotionCueService::class.java).putExtra(EXTRA_MODE, target.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "cues_v2"
        private const val OLD_CHANNEL_ID = "cues"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_MODE = "mode"
        private const val GPS_INTERVAL_MS = 2000L
        private const val STOP_AFTER_MS = 60_000L

        /** Starts the service in [mode]; call it while the app is in the foreground. */
        fun start(context: Context, mode: CueMode) {
            ContextCompat.startForegroundService(
                context, Intent(context, MotionCueService::class.java).putExtra(EXTRA_MODE, mode.name)
            )
        }

        /** Changes the mode of the running service (allowed from the background since it is a foreground service). */
        fun switchTo(context: Context, mode: CueMode) {
            if (mode == CueMode.OFF) {
                context.stopService(Intent(context, MotionCueService::class.java))
            } else {
                context.startService(Intent(context, MotionCueService::class.java).putExtra(EXTRA_MODE, mode.name))
            }
        }
    }
}
