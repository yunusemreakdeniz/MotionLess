package io.github.yunusemreakdeniz.motionless

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var status: TextView
    private lateinit var permissionCard: View
    private lateinit var preview: MotionCueView
    private lateinit var speedLabel: TextView
    private lateinit var speed: Slider
    private lateinit var sensitivity: Slider
    private lateinit var dotSize: Slider
    private lateinit var opacity: Slider
    private lateinit var colors: ChipGroup
    private lateinit var layoutGroup: MaterialButtonToggleGroup
    private lateinit var autoHide: MaterialSwitch

    private var engine: MotionEngine? = null
    private var pendingMode: CueMode? = null
    private var askedNotifications = false
    private var updatingUi = false
    private var backgroundAfterStart = false

    private val colorChips = intArrayOf(R.id.color_white, R.id.color_black, R.id.color_blue, R.id.color_orange)

    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_MODE) refreshUi()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { pendingMode?.let { requestMode(it) } }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val target = pendingMode ?: return@registerForActivityResult
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                requestMode(target)
            } else {
                pendingMode = null
                Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
                refreshUi()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeGroup = findViewById(R.id.mode_group)
        status = findViewById(R.id.status)
        permissionCard = findViewById(R.id.permission_card)
        preview = findViewById(R.id.preview)
        speedLabel = findViewById(R.id.speed_label)
        speed = findViewById(R.id.speed)
        sensitivity = findViewById(R.id.sensitivity)
        dotSize = findViewById(R.id.dot_size)
        opacity = findViewById(R.id.opacity)
        colors = findViewById(R.id.colors)
        layoutGroup = findViewById(R.id.layout_group)
        autoHide = findViewById(R.id.auto_hide)

        preview.forceVisible = true
        loadControls()

        modeGroup.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            requestMode(
                when (id) {
                    R.id.mode_auto -> CueMode.AUTO
                    R.id.mode_on -> CueMode.ON
                    else -> CueMode.OFF
                }
            )
        }
        findViewById<MaterialButton>(R.id.grant).setOnClickListener { openOverlaySettings() }

        val sliderListener = Slider.OnChangeListener { _, _, fromUser -> if (fromUser) saveControls() }
        speed.addOnChangeListener(sliderListener)
        sensitivity.addOnChangeListener(sliderListener)
        dotSize.addOnChangeListener(sliderListener)
        opacity.addOnChangeListener(sliderListener)
        colors.setOnCheckedStateChangeListener { _, _ -> saveControls() }
        layoutGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) saveControls() }
        autoHide.setOnCheckedChangeListener { _, _ -> saveControls() }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Prefs.of(this).registerOnSharedPreferenceChangeListener(modeListener)
        refreshUi()
        engine = MotionEngine(this) { lateral, forward -> preview.onMotion(lateral, forward) }.also {
            if (!it.isAvailable) Toast.makeText(this, R.string.no_sensor, Toast.LENGTH_LONG).show()
            it.start()
        }
    }

    override fun onPause() {
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(modeListener)
        engine?.stop()
        engine = null
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        val current = intent ?: return
        if (current.action != ACTION_START) return
        current.action = null
        val mode = current.getStringExtra(EXTRA_MODE)?.let { runCatching { CueMode.valueOf(it) }.getOrNull() } ?: CueMode.AUTO
        backgroundAfterStart = true
        requestMode(mode)
    }

    /** Asks for whatever the mode needs, then starts or switches the service. */
    private fun requestMode(mode: CueMode) {
        pendingMode = mode
        if (mode == CueMode.OFF) {
            MotionCueService.switchTo(this, CueMode.OFF)
            done()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            pendingMode = null
            backgroundAfterStart = false
            refreshUi()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (mode == CueMode.AUTO && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            locationPermission.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        MotionCueService.start(this, mode)
        done()
        if (backgroundAfterStart) moveTaskToBack(true)
        backgroundAfterStart = false
    }

    private fun done() {
        pendingMode = null
        modeGroup.postDelayed({ refreshUi() }, 300)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun refreshUi() {
        val mode = pendingMode ?: Prefs.mode(this)
        updatingUi = true
        modeGroup.check(
            when (mode) {
                CueMode.OFF -> R.id.mode_off
                CueMode.AUTO -> R.id.mode_auto
                CueMode.ON -> R.id.mode_on
            }
        )
        updatingUi = false
        status.text = when (mode) {
            CueMode.OFF -> getString(R.string.status_off)
            CueMode.AUTO -> getString(R.string.status_auto, speed.value.toInt())
            CueMode.ON -> getString(R.string.status_on)
        }
        permissionCard.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun loadControls() {
        updatingUi = true
        val s = Prefs.load(this)
        speed.value = s.speedThresholdKmh.toFloat().coerceIn(speed.valueFrom, speed.valueTo)
        sensitivity.value = s.sensitivity.toFloat()
        dotSize.value = s.dotSizeDp.toFloat()
        opacity.value = s.opacity.toFloat()
        colors.check(colorChips[s.colorIndex.coerceIn(colorChips.indices)])
        layoutGroup.check(if (s.layout == CueSettings.LAYOUT_GRID) R.id.layout_grid else R.id.layout_edges)
        autoHide.isChecked = s.autoHide
        speedLabel.text = getString(R.string.auto_speed, s.speedThresholdKmh)
        preview.settings = s
        updatingUi = false
    }

    private fun saveControls() {
        if (updatingUi) return
        val s = CueSettings(
            sensitivity = sensitivity.value.toInt(),
            dotSizeDp = dotSize.value.toInt(),
            opacity = opacity.value.toInt(),
            colorIndex = colorChips.indexOf(colors.checkedChipId).coerceAtLeast(0),
            layout = if (layoutGroup.checkedButtonId == R.id.layout_grid) CueSettings.LAYOUT_GRID else CueSettings.LAYOUT_EDGES,
            autoHide = autoHide.isChecked,
            speedThresholdKmh = speed.value.toInt(),
        )
        Prefs.save(this, s)
        preview.settings = s
        speedLabel.text = getString(R.string.auto_speed, s.speedThresholdKmh)
        refreshUi()
    }

    companion object {
        const val ACTION_START = "io.github.yunusemreakdeniz.motionless.START"
        const val EXTRA_MODE = "mode"
    }
}
