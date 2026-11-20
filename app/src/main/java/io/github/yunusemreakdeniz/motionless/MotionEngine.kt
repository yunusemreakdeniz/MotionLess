package io.github.yunusemreakdeniz.motionless

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Turns phone sensor data into the vehicle's horizontal acceleration, in m/s².
 *
 * Gravity tells us which way is down, so the acceleration can be split into two horizontal parts
 * whatever angle the phone is held at:
 * - forward: the direction the back of the phone faces (or its top edge when it lies flat),
 * - lateral: to the right of that.
 * Callbacks arrive on the main thread.
 */
class MotionEngine(
    context: Context,
    private val onMotion: (lateral: Float, forward: Float) -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val useFusion = linear != null && gravitySensor != null

    private val gravity = floatArrayOf(0f, SensorManager.GRAVITY_EARTH, 0f)
    private var lateral = 0f
    private var forward = 0f

    val isAvailable: Boolean get() = useFusion || accelerometer != null

    fun start() {
        if (useFusion) {
            sensors.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
            sensors.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelerometer != null) {
            sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        lateral = 0f
        forward = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gravity[0] = v[0]; gravity[1] = v[1]; gravity[2] = v[2]
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> process(v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in 0..2) gravity[i] = GRAVITY_KEEP * gravity[i] + (1 - GRAVITY_KEEP) * v[i]
                process(v[0] - gravity[0], v[1] - gravity[1], v[2] - gravity[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun process(ax: Float, ay: Float, az: Float) {
        val gl = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gl < 1f) return
        // Unit vector pointing up, in device coordinates.
        val ux = gravity[0] / gl
        val uy = gravity[1] / gl
        val uz = gravity[2] / gl

        // Forward = device -Z projected onto the horizontal plane.
        var fx = uz * ux
        var fy = uz * uy
        var fz = -1f + uz * uz
        var fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 0.35f) {
            // Phone is lying flat: use its top edge (+Y) instead.
            fx = -uy * ux
            fy = 1f - uy * uy
            fz = -uy * uz
            fl = sqrt(fx * fx + fy * fy + fz * fz)
            if (fl < 1e-3f) return
        }
        fx /= fl; fy /= fl; fz /= fl

        // Right = forward x up.
        val lx = fy * uz - fz * uy
        val ly = fz * ux - fx * uz
        val lz = fx * uy - fy * ux

        val aForward = ax * fx + ay * fy + az * fz
        val aLateral = ax * lx + ay * ly + az * lz

        forward += (aForward - forward) * SMOOTHING
        lateral += (aLateral - lateral) * SMOOTHING
        onMotion(lateral, forward)
    }

    private companion object {
        const val SMOOTHING = 0.12f
        const val GRAVITY_KEEP = 0.9f
    }
}
