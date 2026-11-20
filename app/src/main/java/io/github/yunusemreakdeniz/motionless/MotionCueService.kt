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
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Date
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs the cues.
 *
 * ON: the dot overlay is shown while the screen is on.
 *
 * AUTO: GPS is never left running. While the screen is on the service takes short speed readings
 * ("probes"): GPS turns on, the first reading with a speed ends it (or 25 s pass), and GPS turns off.
 * - Idle: probes back off 1 -> 2 -> 4 -> 8 min. The low-power significant-motion sensor triggers a
 *   probe at once when the phone starts moving, and turning the screen on does too.
 * - Cues on: a probe every minute; two probes in a row below half the threshold turn them off, so
 *   short stops at lights do not make them flicker.
 * Screen off stops everything. The ongoing notification switches between the modes.
 */
class MotionCueService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var mode = CueMode.OFF
    private var overlay: MotionCueView? = null
    private var engine: MotionEngine? = null
    private var screenOn = true
    private var hasLocationType = false
    private var lastNotificationKey = ""

    // Auto mode state.
    private var autoActive = false
    private var probing = false
    private var probeLocation: Location? = null
    private var lastSpeedKmh: Int? = null
    private var slowProbes = 0
    private var idleIntervalMs = FIRST_IDLE_INTERVAL_MS
    private var nextProbeAt = 0L
    private var motionArmed = false

    private val probeRunnable = Runnable { startProbe() }
    private val probeTimeout = Runnable { endProbe(null) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != Prefs.KEY_MODE && key != Prefs.KEY_STATUS) overlay?.settings = Prefs.load(this)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            refresh()
        }
    }

    // All four callbacks are overridden: before Android 11 they are abstract and the system calls them.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onProbeLocation(location)
        override fun onProviderEnabled(provider: String) = updateNotification(force = true)
        override fun onProviderDisabled(provider: String) = updateNotification(force = true)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val motionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            motionArmed = false
            if (mode == CueMode.AUTO && screenOn && !autoActive && !probing) {
                idleIntervalMs = FIRST_IDLE_INTERVAL_MS
                startProbe()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { CueMode.valueOf(it) }.getOrNull() }
            ?: Prefs.mode(this).takeIf { it != CueMode.OFF }
            ?: CueMode.ON
        startInForeground(buildNotification(requested))
        return if (applyMode(requested)) START_STICKY else START_NOT_STICKY
    }

    /**
     * Switches the running service to [requested] in place. Notification buttons and the tile call
     * this directly (through [ModeReceiver]) instead of restarting the service: a restart from the
     * background may be refused, especially for the location type that Auto mode needs.
     * Returns false when the service stops.
     */
    fun applyMode(requested: CueMode): Boolean {
        if (requested == CueMode.OFF || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return false
        }
        if (requested == CueMode.AUTO && !hasLocationType && hasLocationPermission()) {
            // Started without location access (e.g. permission granted later): try to add it now.
            startInForeground(buildNotification(requested))
        }
        val changed = requested != mode
        mode = requested
        Prefs.setMode(this, mode)
        if (changed) {
            autoActive = false
            slowProbes = 0
            idleIntervalMs = FIRST_IDLE_INTERVAL_MS
            stopProbe()
            handler.removeCallbacks(probeRunnable)
        }
        refresh(probeNow = changed)
        updateNotification(force = true)
        CueTileService.refresh(this)
        return true
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        stopProbe()
        disarmMotion()
        showOverlay(false)
        unregisterReceiver(screenReceiver)
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        Prefs.setMode(this, CueMode.OFF)
        Prefs.setStatus(this, "")
        CueTileService.refresh(this)
        super.onDestroy()
    }

    /** Brings overlay, probes and the motion trigger in line with the mode and the screen state. */
    private fun refresh(probeNow: Boolean = true) {
        when {
            mode == CueMode.ON -> {
                stopProbe()
                handler.removeCallbacks(probeRunnable)
                disarmMotion()
                showOverlay(screenOn)
            }
            mode == CueMode.AUTO && !screenOn -> {
                stopProbe()
                handler.removeCallbacks(probeRunnable)
                disarmMotion()
                autoActive = false
                slowProbes = 0
                idleIntervalMs = FIRST_IDLE_INTERVAL_MS
                showOverlay(false)
            }
            mode == CueMode.AUTO -> {
                showOverlay(autoActive)
                if (!autoActive) armMotion()
                if (probeNow && !probing) startProbe()
            }
            else -> Unit
        }
        updateNotification()
    }

    // ---- Auto mode: short GPS probes ----

    private fun startProbe() {
        handler.removeCallbacks(probeRunnable)
        if (mode != CueMode.AUTO || !screenOn || probing) return
        if (!hasLocationPermission() || !locationEnabled()) {
            scheduleProbe(MAX_IDLE_INTERVAL_MS)
            updateNotification()
            return
        }
        val locations = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, PROBE_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
        } catch (_: Exception) {
            scheduleProbe(MAX_IDLE_INTERVAL_MS)
            return
        }
        probing = true
        probeLocation = null
        handler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
        updateNotification()
    }

    private fun stopProbe() {
        handler.removeCallbacks(probeTimeout)
        if (!probing) return
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
        probing = false
        probeLocation = null
    }

    private fun onProbeLocation(location: Location) {
        if (!probing) return
        val previous = probeLocation
        probeLocation = location
        val metersPerSecond = when {
            location.hasSpeed() -> location.speed
            previous != null && location.time > previous.time ->
                location.distanceTo(previous) / ((location.time - previous.time) / 1000f)
            else -> return // wait for a second fix
        }
        endProbe(metersPerSecond * 3.6f)
    }

    /** [kmh] is null when no reading came in time (no GPS signal, tunnel). */
    private fun endProbe(kmh: Float?) {
        stopProbe()
        if (mode != CueMode.AUTO || !screenOn) return
        val threshold = Prefs.load(this).speedThresholdKmh
        if (kmh != null) lastSpeedKmh = kmh.roundToInt()

        if (autoActive) {
            when {
                kmh == null || kmh >= threshold * 0.5f -> slowProbes = 0
                else -> slowProbes++
            }
            if (slowProbes >= SLOW_PROBES_TO_STOP) {
                autoActive = false
                slowProbes = 0
                idleIntervalMs = FIRST_IDLE_INTERVAL_MS
                scheduleProbe(idleIntervalMs)
            } else {
                scheduleProbe(if (slowProbes > 0) CONFIRM_INTERVAL_MS else ACTIVE_INTERVAL_MS)
            }
        } else if (kmh != null && kmh >= threshold) {
            autoActive = true
            slowProbes = 0
            scheduleProbe(ACTIVE_INTERVAL_MS)
        } else {
            scheduleProbe(idleIntervalMs)
            idleIntervalMs = min(idleIntervalMs * 2, MAX_IDLE_INTERVAL_MS)
        }
        showOverlay(autoActive)
        if (autoActive) disarmMotion() else armMotion()
        updateNotification()
    }

    private fun scheduleProbe(delayMs: Long) {
        handler.removeCallbacks(probeRunnable)
        nextProbeAt = SystemClock.elapsedRealtime() + delayMs
        handler.postDelayed(probeRunnable, delayMs)
    }

    /** Low-power one-shot sensor that fires when the phone starts moving (walking, a vehicle). */
    private fun armMotion() {
        if (motionArmed) return
        val sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        motionArmed = sensors.requestTriggerSensor(motionListener, sensor)
    }

    private fun disarmMotion() {
        if (!motionArmed) return
        val sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensors.cancelTriggerSensor(motionListener, it) }
        motionArmed = false
    }

    private fun locationEnabled(): Boolean =
        LocationManagerCompat.isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as LocationManager)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            hasLocationType = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasLocationPermission()
        } catch (_: Exception) {
            // The location type is refused while the app is in the background; keep running without it.
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fallback)
            } catch (_: Exception) {
            }
            hasLocationType = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        }
    }

    private fun updateNotification(force: Boolean = false) {
        if (mode == CueMode.OFF) return
        val key = "$mode|$autoActive|$screenOn|$probing|$lastSpeedKmh|$nextProbeAt|${locationEnabled()}"
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key
        val notification = buildNotification(mode)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
    }

    private fun nextProbeClock(): String {
        val wall = System.currentTimeMillis() + (nextProbeAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return DateFormat.getTimeFormat(this).format(Date(wall))
    }

    private fun buildNotification(forMode: CueMode): Notification {
        val threshold = Prefs.load(this).speedThresholdKmh
        val title: String
        val text: String
        if (forMode == CueMode.AUTO) {
            title = getString(if (autoActive && screenOn) R.string.notif_auto_active else R.string.notif_auto_waiting)
            val speedPart = lastSpeedKmh?.let { getString(R.string.notif_last_speed, it) } ?: ""
            text = when {
                !hasLocationPermission() -> getString(R.string.notif_auto_no_permission)
                !locationEnabled() -> getString(R.string.notif_auto_location_off)
                !hasLocationType -> getString(R.string.notif_auto_reopen)
                !screenOn -> getString(R.string.notif_auto_screen_off)
                probing -> getString(R.string.notif_auto_checking, threshold)
                else -> speedPart + getString(R.string.notif_auto_next, nextProbeClock(), threshold)
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

    private fun modeIntent(target: CueMode): PendingIntent = PendingIntent.getBroadcast(
        this, 10 + target.ordinal,
        Intent(this, ModeReceiver::class.java).setAction(ModeReceiver.ACTION_SET_MODE).putExtra(EXTRA_MODE, target.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "cues_v2"
        private const val OLD_CHANNEL_ID = "cues"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_MODE = "mode"

        private const val PROBE_INTERVAL_MS = 1000L
        private const val PROBE_TIMEOUT_MS = 25_000L
        private const val FIRST_IDLE_INTERVAL_MS = 60_000L
        private const val MAX_IDLE_INTERVAL_MS = 8 * 60_000L
        private const val ACTIVE_INTERVAL_MS = 60_000L
        private const val CONFIRM_INTERVAL_MS = 30_000L
        private const val SLOW_PROBES_TO_STOP = 2

        /** The running service, if any (same process), so mode changes need no restart. */
        @Volatile
        var instance: MotionCueService? = null
            private set

        /** Starts the service in [mode]; call it while the app is in the foreground. */
        fun start(context: Context, mode: CueMode) {
            ContextCompat.startForegroundService(
                context, Intent(context, MotionCueService::class.java).putExtra(EXTRA_MODE, mode.name)
            )
        }

        /** Changes the mode of the running service in place; falls back to a service call when it is not running. */
        fun switchTo(context: Context, mode: CueMode) {
            val running = instance
            if (running != null) {
                running.applyMode(mode)
            } else if (mode == CueMode.OFF) {
                context.stopService(Intent(context, MotionCueService::class.java))
            } else {
                context.startService(Intent(context, MotionCueService::class.java).putExtra(EXTRA_MODE, mode.name))
            }
        }
    }
}
