package io.github.yunusemreakdeniz.motionless

import android.content.Context
import android.content.SharedPreferences

/** OFF: nothing runs. AUTO: cues appear while the screen is on and the vehicle is fast enough. ON: always. */
enum class CueMode { OFF, AUTO, ON }

data class CueSettings(
    val sensitivity: Int = 5,
    val dotSizeDp: Int = 10,
    val opacity: Int = 80,
    val colorIndex: Int = 0,
    val layout: Int = LAYOUT_EDGES,
    val autoHide: Boolean = false,
    val speedThresholdKmh: Int = 15,
) {
    val color: Int get() = COLORS[colorIndex.coerceIn(COLORS.indices)]

    companion object {
        const val LAYOUT_EDGES = 0
        const val LAYOUT_GRID = 1

        /** White, black, blue, orange. */
        val COLORS = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF111111.toInt(),
            0xFF2F80ED.toInt(),
            0xFFFF7A1A.toInt(),
        )
    }
}

object Prefs {
    const val KEY_MODE = "mode"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_DOT_SIZE = "dot_size"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_COLOR = "color"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_AUTO_HIDE = "auto_hide"
    private const val KEY_SPEED = "speed_threshold"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("motionless", Context.MODE_PRIVATE)

    fun load(context: Context): CueSettings {
        val p = of(context)
        val d = CueSettings()
        return CueSettings(
            sensitivity = p.getInt(KEY_SENSITIVITY, d.sensitivity),
            dotSizeDp = p.getInt(KEY_DOT_SIZE, d.dotSizeDp),
            opacity = p.getInt(KEY_OPACITY, d.opacity),
            colorIndex = p.getInt(KEY_COLOR, d.colorIndex),
            layout = p.getInt(KEY_LAYOUT, d.layout),
            autoHide = p.getBoolean(KEY_AUTO_HIDE, d.autoHide),
            speedThresholdKmh = p.getInt(KEY_SPEED, d.speedThresholdKmh),
        )
    }

    fun save(context: Context, s: CueSettings) {
        of(context).edit()
            .putInt(KEY_SENSITIVITY, s.sensitivity)
            .putInt(KEY_DOT_SIZE, s.dotSizeDp)
            .putInt(KEY_OPACITY, s.opacity)
            .putInt(KEY_COLOR, s.colorIndex)
            .putInt(KEY_LAYOUT, s.layout)
            .putBoolean(KEY_AUTO_HIDE, s.autoHide)
            .putInt(KEY_SPEED, s.speedThresholdKmh)
            .apply()
    }

    fun mode(context: Context): CueMode =
        runCatching { CueMode.valueOf(of(context).getString(KEY_MODE, null) ?: "OFF") }.getOrDefault(CueMode.OFF)

    fun setMode(context: Context, mode: CueMode) {
        of(context).edit().putString(KEY_MODE, mode.name).apply()
    }
}
