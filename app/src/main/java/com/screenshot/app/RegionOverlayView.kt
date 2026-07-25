package com.screenshot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Overlay view for selecting the time region on the video frame.
 * User drags to draw a rectangle where the timestamp watermark appears.
 */
class RegionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** The selected region as a fraction (0..1) of the view size. */
    var region: RectF = RectF(0.6f, 0.85f, 0.98f, 0.98f)
        private set

    var onRegionChanged: ((RectF) -> Unit)? = null

    var isSelecting: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FF5722")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30FF5722")
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private var dragMode = DragMode.NONE
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var regionAtDragStart = RectF()

    private enum class DragMode {
        NONE, MOVE, RESIZE_BR, CREATE
    }

    override fun onDraw(canvas: Canvas) {
        if (!isSelecting) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw region rect in pixel coords
        val pixelRect = RectF(
            region.left * w,
            region.top * h,
            region.right * w,
            region.bottom * h
        )

        canvas.drawRect(pixelRect, fillPaint)
        canvas.drawRect(pixelRect, borderPaint)

        // Corner handles
        val handleSize = 20f
        canvas.drawCircle(pixelRect.left, pixelRect.top, handleSize, handlePaint)
        canvas.drawCircle(pixelRect.right, pixelRect.top, handleSize, handlePaint)
        canvas.drawCircle(pixelRect.left, pixelRect.bottom, handleSize, handlePaint)
        canvas.drawCircle(pixelRect.right, pixelRect.bottom, handleSize, handlePaint)

        // Hint text
        hintPaint.textSize = w * 0.035f
        canvas.drawText("拖动调整时间水印识别区域", w / 2, pixelRect.top - 16f, hintPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSelecting) return false

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false

        val fx = event.x / w
        val fy = event.y / h

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = fx
                touchStartY = fy
                regionAtDragStart = RectF(region)

                // Check if touching inside region → move
                if (fx in region.left..region.right && fy in region.top..region.bottom) {
                    dragMode = DragMode.MOVE
                } else {
                    // Start creating new region
                    dragMode = DragMode.CREATE
                    region.left = fx
                    region.top = fy
                    region.right = fx
                    region.bottom = fy
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = fx - touchStartX
                val dy = fy - touchStartY

                when (dragMode) {
                    DragMode.MOVE -> {
                        val newLeft = (regionAtDragStart.left + dx).coerceIn(0f, 1f - (regionAtDragStart.right - regionAtDragStart.left))
                        val newTop = (regionAtDragStart.top + dy).coerceIn(0f, 1f - (regionAtDragStart.bottom - regionAtDragStart.top))
                        val width = regionAtDragStart.right - regionAtDragStart.left
                        val height = regionAtDragStart.bottom - regionAtDragStart.top
                        region.left = newLeft
                        region.top = newTop
                        region.right = newLeft + width
                        region.bottom = newTop + height
                    }
                    DragMode.CREATE -> {
                        region.right = fx.coerceIn(region.left, 1f)
                        region.bottom = fy.coerceIn(region.top, 1f)
                    }
                    else -> {}
                }

                onRegionChanged?.invoke(region)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                // Ensure region is normalized (left < right, top < bottom)
                if (region.left > region.right) {
                    val tmp = region.left; region.left = region.right; region.right = tmp
                }
                if (region.top > region.bottom) {
                    val tmp = region.top; region.top = region.bottom; region.bottom = tmp
                }
                // Minimum size
                if (region.right - region.left < 0.05f) region.right = (region.left + 0.05f).coerceAtMost(1f)
                if (region.bottom - region.top < 0.03f) region.bottom = (region.top + 0.03f).coerceAtMost(1f)

                onRegionChanged?.invoke(region)
                invalidate()
                return true
            }
        }
        return false
    }
}
