package io.github.yunusemreakdeniz.motionless

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws the motion cue dots, scattered irregularly along both screen edges (or across the whole
 * screen). The dots shift against the vehicle's acceleration (speeding up pushes them down, a left
 * turn pushes them right), the way loose objects would move, so what the eyes see matches what the
 * inner ear feels.
 */
class MotionCueView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var targetX = 0f
    private var targetY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var visibility = 1f
    private var lastActive = 0L
    private var attached = false
    private var framePending = false

    /** Dot centres as x, y pairs; rebuilt when the size, count or layout changes. */
    private var points = FloatArray(0)
    private var pointsKey = ""

    /** Keeps the dots visible even when auto-hide is on (used by the in-app preview). */
    var forceVisible = false

    var settings: CueSettings = CueSettings()
        set(value) {
            field = value
            invalidate()
            wake()
        }

    fun onMotion(lateral: Float, forward: Float) {
        val pxPerUnit = settings.sensitivity * 4f * density
        val limit = MAX_SHIFT_DP * density
        targetX = (-lateral * pxPerUnit).coerceIn(-limit, limit)
        targetY = (forward * pxPerUnit).coerceIn(-limit, limit)
        if (sqrt(lateral * lateral + forward * forward) > ACTIVE_THRESHOLD) {
            lastActive = SystemClock.uptimeMillis()
        }
        wake()
    }

    /** Starts the frame loop if it went to sleep; it stops again once the dots settle. */
    private fun wake() {
        if (!attached || framePending) return
        framePending = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastActive = SystemClock.uptimeMillis()
        attached = true
        wake()
    }

    override fun onDetachedFromWindow() {
        attached = false
        framePending = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (!attached) return
        val calm = SystemClock.uptimeMillis() - lastActive > HIDE_AFTER_MS
        val wanted = if (settings.autoHide && calm && !forceVisible) 0f else 1f
        val dx = targetX - currentX
        val dy = targetY - currentY
        val dv = wanted - visibility
        val settled = abs(dx) < SETTLE_PX && abs(dy) < SETTLE_PX && abs(dv) < 0.01f
        if (settled) {
            // Snap to the target and stop drawing until the next sensor change.
            if (currentX != targetX || currentY != targetY || visibility != wanted) {
                currentX = targetX; currentY = targetY; visibility = wanted
                invalidate()
            }
            return
        }
        currentX += dx * FOLLOW
        currentY += dy * FOLLOW
        visibility += dv * FADE
        invalidate()
        framePending = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility < 0.01f || width == 0 || height == 0) return
        ensurePoints()

        val radius = settings.dotSizeDp * density / 2f
        val alpha = (settings.opacity / 100f * visibility * 255f).toInt().coerceIn(0, 255)
        val color = settings.color
        fill.color = color
        fill.alpha = alpha
        ring.color = if (isLight(color)) Color.BLACK else Color.WHITE
        ring.alpha = (alpha * 0.45f).toInt()
        ring.strokeWidth = max(1f, radius * 0.25f)

        var i = 0
        while (i < points.size) {
            val x = points[i] + currentX
            val y = points[i + 1] + currentY
            canvas.drawCircle(x, y, radius, fill)
            canvas.drawCircle(x, y, radius, ring)
            i += 2
        }
    }

    private fun ensurePoints() {
        val count = settings.dotCount.coerceIn(4, 200)
        val key = "$width|$height|$count|${settings.layout}"
        if (key == pointsKey) return
        pointsKey = key
        val random = Random(SEED)
        val out = ArrayList<Float>(count * 2)
        if (settings.layout == CueSettings.LAYOUT_GRID) {
            scatter(random, count, 0f, width.toFloat(), height.toFloat(), mirror = false, out)
        } else {
            val band = max(BAND_MIN_DP * density, width * BAND_FRACTION)
            val perSide = (count + 1) / 2
            scatter(random, perSide, EDGE_MARGIN_DP * density, band, height.toFloat(), mirror = false, out)
            scatter(random, count - perSide, EDGE_MARGIN_DP * density, band, height.toFloat(), mirror = true, out)
        }
        points = out.toFloatArray()
    }

    /**
     * Places [n] dots at random in the strip x in [left, right), keeping them roughly evenly apart
     * (dart throwing with a shrinking minimum distance). [mirror] puts the strip on the right edge.
     */
    private fun scatter(random: Random, n: Int, left: Float, right: Float, h: Float, mirror: Boolean, out: MutableList<Float>) {
        if (n <= 0 || right <= left) return
        val placed = ArrayList<Float>(n * 2)
        var minDist = sqrt((right - left) * h / n) * 0.75f
        var attempts = 0
        while (placed.size < n * 2) {
            val x = left + random.nextFloat() * (right - left)
            val y = random.nextFloat() * h
            var ok = true
            var j = 0
            while (j < placed.size) {
                val dx = placed[j] - x
                val dy = placed[j + 1] - y
                if (dx * dx + dy * dy < minDist * minDist) { ok = false; break }
                j += 2
            }
            if (ok) {
                placed.add(x); placed.add(y)
            } else if (++attempts > 60) {
                minDist *= 0.9f
                attempts = 0
            }
        }
        var j = 0
        while (j < placed.size) {
            out.add(if (mirror) width - placed[j] else placed[j])
            out.add(placed[j + 1])
            j += 2
        }
    }

    private fun isLight(color: Int): Boolean =
        Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 > 150

    private companion object {
        const val SEED = 20261120
        const val MAX_SHIFT_DP = 56f
        const val ACTIVE_THRESHOLD = 0.6f
        const val HIDE_AFTER_MS = 4000L
        const val FOLLOW = 0.2f
        const val FADE = 0.08f
        const val SETTLE_PX = 0.3f
        const val EDGE_MARGIN_DP = 10f
        const val BAND_MIN_DP = 64f
        const val BAND_FRACTION = 0.2f
    }
}
