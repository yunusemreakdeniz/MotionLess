package io.github.yunusemreakdeniz.motionless

import android.Manifest
import android.content.Intent
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

    private lateinit var toggle: MaterialButton
    private lateinit var status: TextView
    private lateinit var permissionCard: View
    private lateinit var preview: MotionCueView
    private lateinit var sensitivity: Slider
    private lateinit var dotSize: Slider
    private lateinit var opacity: Slider
    private lateinit var colors: ChipGroup
    private lateinit var layoutGroup: MaterialButtonToggleGroup
    private lateinit var autoHide: MaterialSwitch

    private var engine: MotionEngine? = null
    private var askedNotifications = false
    private var loading = false

    private val colorChips = intArrayOf(R.id.color_white, R.id.color_black, R.id.color_blue, R.id.color_orange)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startCues() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        permissionCard = findViewById(R.id.permission_card)
        preview = findViewById(R.id.preview)
        sensitivity = findViewById(R.id.sensitivity)
        dotSize = findViewById(R.id.dot_size)
        opacity = findViewById(R.id.opacity)
        colors = findViewById(R.id.colors)
        layoutGroup = findViewById(R.id.layout_group)
        autoHide = findViewById(R.id.auto_hide)

        preview.forceVisible = true
        loadControls()

        toggle.setOnClickListener {
            if (Prefs.isRunning(this)) {
                MotionCueService.stop(this)
                toggle.postDelayed({ refreshUi() }, 300)
            } else {
                startCues()
            }
        }
        findViewById<MaterialButton>(R.id.grant).setOnClickListener { openOverlaySettings() }

        val sliderListener = Slider.OnChangeListener { _, _, fromUser -> if (fromUser) saveControls() }
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
        refreshUi()
        engine = MotionEngine(this) { lateral, forward -> preview.onMotion(lateral, forward) }.also {
            if (!it.isAvailable) Toast.makeText(this, R.string.no_sensor, Toast.LENGTH_LONG).show()
            it.start()
        }
    }

    override fun onPause() {
        engine?.stop()
        engine = null
        super.onPause()
    }

    private fun handleIntent(intent: Intent?) {
        val current = intent ?: return
        if (current.action != ACTION_START) return
        current.action = null
        if (startCues()) moveTaskToBack(true)
    }

    /** Returns true when the service was started. */
    private fun startCues(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return false
        }
        MotionCueService.start(this)
        toggle.postDelayed({ refreshUi() }, 300)
        return true
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun refreshUi() {
        val running = Prefs.isRunning(this)
        toggle.setText(if (running) R.string.stop else R.string.start)
        status.setText(if (running) R.string.status_on else R.string.status_off)
        permissionCard.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
    }

    private fun loadControls() {
        loading = true
        val s = Prefs.load(this)
        sensitivity.value = s.sensitivity.toFloat()
        dotSize.value = s.dotSizeDp.toFloat()
        opacity.value = s.opacity.toFloat()
        colors.check(colorChips[s.colorIndex.coerceIn(colorChips.indices)])
        layoutGroup.check(if (s.layout == CueSettings.LAYOUT_GRID) R.id.layout_grid else R.id.layout_edges)
        autoHide.isChecked = s.autoHide
        preview.settings = s
        loading = false
    }

    private fun saveControls() {
        if (loading) return
        val s = CueSettings(
            sensitivity = sensitivity.value.toInt(),
            dotSizeDp = dotSize.value.toInt(),
            opacity = opacity.value.toInt(),
            colorIndex = colorChips.indexOf(colors.checkedChipId).coerceAtLeast(0),
            layout = if (layoutGroup.checkedButtonId == R.id.layout_grid) CueSettings.LAYOUT_GRID else CueSettings.LAYOUT_EDGES,
            autoHide = autoHide.isChecked,
        )
        Prefs.save(this, s)
        preview.settings = s
    }

    companion object {
        const val ACTION_START = "io.github.yunusemreakdeniz.motionless.START"
    }
}
