package io.github.yunusemreakdeniz.motionless

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws the motion cue dots. The dots shift against the vehicle's acceleration (speeding up pushes
 * them down, a left turn pushes them right), the way loose objects would move, so what the eyes see
 * matches what the inner ear feels.
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
    private var ticking = false

    /** Keeps the dots visible even when auto-hide is on (used by the in-app preview). */
    var forceVisible = false

    var settings: CueSettings = CueSettings()
        set(value) {
            field = value
            invalidate()
        }

    fun onMotion(lateral: Float, forward: Float) {
        val pxPerUnit = settings.sensitivity * 4f * density
        val limit = MAX_SHIFT_DP * density
        targetX = (-lateral * pxPerUnit).coerceIn(-limit, limit)
        targetY = (forward * pxPerUnit).coerceIn(-limit, limit)
        if (sqrt(lateral * lateral + forward * forward) > ACTIVE_THRESHOLD) {
            lastActive = SystemClock.uptimeMillis()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastActive = SystemClock.uptimeMillis()
        ticking = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        ticking = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!ticking) return
        currentX += (targetX - currentX) * FOLLOW
        currentY += (targetY - currentY) * FOLLOW
        val calm = SystemClock.uptimeMillis() - lastActive > HIDE_AFTER_MS
        val wanted = if (settings.autoHide && calm && !forceVisible) 0f else 1f
        visibility += (wanted - visibility) * FADE
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility < 0.01f || width == 0 || height == 0) return

        val radius = settings.dotSizeDp * density / 2f
        val alpha = (settings.opacity / 100f * visibility * 255f).toInt().coerceIn(0, 255)
        val color = settings.color
        fill.color = color
        fill.alpha = alpha
        ring.color = if (isLight(color)) Color.BLACK else Color.WHITE
        ring.alpha = (alpha * 0.45f).toInt()
        ring.strokeWidth = max(1f, radius * 0.25f)

        if (settings.layout == CueSettings.LAYOUT_GRID) {
            val step = GRID_SPACING_DP * density
            var y = step / 2f
            while (y < height + step) {
                var x = step / 2f
                while (x < width + step) {
                    dot(canvas, x + currentX, y + currentY, radius)
                    x += step
                }
                y += step
            }
        } else {
            val step = EDGE_SPACING_DP * density
            val inner = EDGE_MARGIN_DP * density
            val outer = inner + step * 0.8f
            var y = step * 0.75f
            var row = 0
            while (y < height) {
                val stagger = if (row % 2 == 0) 0f else step * 0.4f
                for (x in floatArrayOf(inner, outer)) {
                    dot(canvas, x + currentX, y + stagger + currentY, radius)
                    dot(canvas, width - x + currentX, y + stagger + currentY, radius)
                }
                y += step
                row++
            }
        }
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r, fill)
        canvas.drawCircle(x, y, r, ring)
    }

    private fun isLight(color: Int): Boolean =
        Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 > 150

    private companion object {
        const val MAX_SHIFT_DP = 56f
        const val ACTIVE_THRESHOLD = 0.6f
        const val HIDE_AFTER_MS = 4000L
        const val FOLLOW = 0.2f
        const val FADE = 0.08f
        const val EDGE_SPACING_DP = 46f
        const val EDGE_MARGIN_DP = 18f
        const val GRID_SPACING_DP = 72f
    }
}
