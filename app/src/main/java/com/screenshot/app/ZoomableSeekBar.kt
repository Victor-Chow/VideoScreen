package com.screenshot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Custom seek bar with pinch-to-zoom on the time scale.
 *
 * - The bar is wide (64dp) for easy touch interaction.
 * - Pinch zoom changes the visible time window.
 * - Horizontal drag seeks through the video.
 */
class ZoomableSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Public state ---

    /** Total video duration in ms */
    var durationMs: Long = 0L
        set(value) {
            field = value
            if (windowEndMs > value) windowEndMs = value
            invalidate()
        }

    /** Current playback position in ms */
    var positionMs: Long = 0L
        set(value) {
            field = value
            ensurePositionVisible()
            invalidate()
        }

    /** Called when user drags to seek */
    var onSeekListener: ((positionMs: Long) -> Unit)? = null

    // --- Zoom window state ---

    /** Visible time window start in ms */
    private var windowStartMs: Long = 0L

    /** Visible time window end in ms */
    private var windowEndMs: Long = 0L
        set(value) {
            field = max(0L, min(value, durationMs))
        }

    /** Minimum visible window in ms (zoomed all the way in) */
    private val minWindowMs: Long = 3_000L

    // --- Gesture state ---

    private var isDragging = false
    private var dragStartX = 0f
    private var dragStartWindowStart = 0L

    // --- Paints ---

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0303030")
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1976D2")
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF5722")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        strokeWidth = 1f
    }

    private val barRect = RectF()

    // --- Scale (pinch zoom) ---

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return durationMs > 0L
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (durationMs <= 0L) return false

            val currentWindow = windowEndMs - windowStartMs
            val scaleFactor = 1f / detector.scaleFactor
            val newWindow = (currentWindow * scaleFactor).toLong()
                .coerceIn(minWindowMs, durationMs)

            // Focus point: keep the pinch center stable
            val focusRatio = (detector.focusX - paddingLeft) / (width - paddingLeft - paddingRight).coerceAtLeast(1)
            val focusMs = windowStartMs + (currentWindow * focusRatio).toLong()

            windowStartMs = (focusMs - (newWindow * focusRatio).toLong())
                .coerceIn(0L, durationMs - newWindow)
            windowEndMs = windowStartMs + newWindow

            ensurePositionVisible()
            invalidate()
            return true
        }
    })

    // --- Drag (seek / pan) ---

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (durationMs <= 0L) return false

            val viewWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            val windowSize = windowEndMs - windowStartMs

            // If zoomed in (window < duration), pan the window
            if (windowSize < durationMs) {
                val msPerPx = windowSize.toFloat() / viewWidth
                val panMs = (distanceX * msPerPx).toLong()
                windowStartMs = (windowStartMs + panMs).coerceIn(0L, durationMs - windowSize)
                windowEndMs = windowStartMs + windowSize
            }

            // Always update position based on touch X
            val touchX = e2.x - paddingLeft
            val ratio = (touchX / viewWidth).coerceIn(0f, 1f)
            val newPos = windowStartMs + (windowSize * ratio).toLong()
            positionMs = newPos.coerceIn(0L, durationMs)
            onSeekListener?.invoke(positionMs)
            invalidate()
            return true
        }
    })

    // --- Lifecycle ---

    init {
        // Reset window to full when duration changes
        windowEndMs = durationMs
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                parent.requestDisallowInterceptTouchEvent(true)

                // Jump to touch position
                if (durationMs > 0L) {
                    val viewWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
                    val ratio = ((event.x - paddingLeft) / viewWidth).coerceIn(0f, 1f)
                    val windowSize = windowEndMs - windowStartMs
                    val newPos = windowStartMs + (windowSize * ratio).toLong()
                    positionMs = newPos.coerceIn(0L, durationMs)
                    onSeekListener?.invoke(positionMs)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val w = right - left
        val h = bottom - top

        if (w <= 0f || h <= 0f) return

        // Background bar
        barRect.set(left, top, right, bottom)
        canvas.drawRoundRect(barRect, 8f, 8f, bgPaint)

        if (durationMs <= 0L) return

        val windowSize = windowEndMs - windowStartMs
        if (windowSize <= 0L) return

        // Progress fill
        val progressRatio = if (windowSize > 0) {
            ((positionMs - windowStartMs).toFloat() / windowSize).coerceIn(0f, 1f)
        } else 0f
        barRect.set(left, top, left + w * progressRatio, bottom)
        canvas.drawRoundRect(barRect, 8f, 8f, progressPaint)

        // Tick marks
        drawTickMarks(canvas, left, top, right, bottom, w)

        // Position indicator line
        val indicatorX = left + w * progressRatio
        canvas.drawLine(indicatorX, top, indicatorX, bottom, indicatorPaint)

        // Time labels
        val startText = formatTime(windowStartMs)
        val endText = formatTime(windowEndMs)
        canvas.drawText(startText, left + 40f, top + h / 2 + textPaint.textSize / 3, textPaint)
        canvas.drawText(endText, right - 40f, top + h / 2 + textPaint.textSize / 3, textPaint)
    }

    private fun drawTickMarks(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, w: Float) {
        val windowSize = windowEndMs - windowStartMs
        // Choose tick interval based on window size
        val intervals = longArrayOf(500, 1000, 2000, 5000, 10000, 30000, 60000, 300000, 600000)
        var interval = intervals.last()
        for (iv in intervals) {
            val tickCount = windowSize / iv
            if (tickCount in 3..30) {
                interval = iv
                break
            }
        }

        val firstTick = ((windowStartMs + interval - 1) / interval) * interval
        var tick = firstTick
        while (tick <= windowEndMs) {
            val ratio = (tick - windowStartMs).toFloat() / windowSize
            val x = left + w * ratio
            canvas.drawLine(x, top, x, top + 12f, tickPaint)
            canvas.drawLine(x, bottom - 12f, x, bottom, tickPaint)
            tick += interval
        }
    }

    // --- Helpers ---

    private fun ensurePositionVisible() {
        if (durationMs <= 0L) return
        val windowSize = windowEndMs - windowStartMs
        if (windowSize <= 0L) return

        if (positionMs < windowStartMs) {
            val shift = windowStartMs - positionMs
            windowStartMs -= shift
            windowEndMs = windowStartMs + windowSize
        } else if (positionMs > windowEndMs) {
            val shift = positionMs - windowEndMs
            windowStartMs += shift
            windowEndMs = windowStartMs + windowSize
        }

        // Clamp
        if (windowEndMs > durationMs) {
            windowEndMs = durationMs
            windowStartMs = (durationMs - windowSize).coerceAtLeast(0L)
        }
        if (windowStartMs < 0L) {
            windowStartMs = 0L
            windowEndMs = min(windowSize, durationMs)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val frac = (ms % 1000) / 10
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d.%02d", h, m, s, frac)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d.%02d", m, s, frac)
        }
    }

    /** Reset zoom to show full video */
    fun resetZoom() {
        windowStartMs = 0L
        windowEndMs = durationMs
        invalidate()
    }
}
