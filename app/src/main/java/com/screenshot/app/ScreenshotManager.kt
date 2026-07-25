package com.screenshot.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages screenshot capture, watermark rendering, and saving.
 */
class ScreenshotManager(private val context: Context) {

    data class CapturedFrame(
        val bitmap: Bitmap,
        val timestampMs: Long,
        val videoDate: Date?
    )

    private val captures = mutableListOf<CapturedFrame>()

    val captureCount: Int get() = captures.size

    /**
     * Capture a frame at the given position from the video URI.
     * Uses MediaMetadataRetriever for accurate frame extraction.
     */
    fun captureFrame(
        videoPath: String?,
        positionMs: Long,
        videoDate: Date?
    ): Bitmap? {
        if (videoPath == null) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            // getFrameAtTime takes microseconds
            val bitmap = retriever.getFrameAtTime(
                positionMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (bitmap != null) {
                captures.add(CapturedFrame(bitmap, positionMs, videoDate))
            }
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Capture frame using file descriptor (for content:// URIs that may not have a file path).
     */
    fun captureFrameFromFd(
        fd: android.os.ParcelFileDescriptor?,
        positionMs: Long,
        videoDate: Date?
    ): Bitmap? {
        if (fd == null) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(fd.fileDescriptor)
            val bitmap = retriever.getFrameAtTime(
                positionMs * 1000,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            if (bitmap != null) {
                captures.add(CapturedFrame(bitmap, positionMs, videoDate))
            }
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * Apply timestamp watermark to a bitmap and return a new bitmap.
     */
    fun applyWatermark(source: Bitmap, position: WatermarkPosition, timestampMs: Long, videoDate: Date?): Bitmap {
        if (position == WatermarkPosition.NONE) return source

        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // Build timestamp string
        val watermarkText = buildWatermarkText(timestampMs, videoDate)

        // Paint
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = result.width * 0.03f  // 3% of image width
            typeface = Typeface.MONOSPACE
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        val textWidth = paint.measureText(watermarkText)
        val textHeight = paint.fontMetrics.let { it.descent - it.ascent }
        val margin = result.width * 0.03f

        val x = when (position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.BOTTOM_LEFT -> margin
            WatermarkPosition.TOP_RIGHT, WatermarkPosition.BOTTOM_RIGHT -> result.width - textWidth - margin
            WatermarkPosition.NONE -> 0f
        }

        val y = when (position) {
            WatermarkPosition.TOP_LEFT, WatermarkPosition.TOP_RIGHT -> margin + textHeight
            WatermarkPosition.BOTTOM_LEFT, WatermarkPosition.BOTTOM_RIGHT -> result.height - margin
            WatermarkPosition.NONE -> 0f
        }

        canvas.drawText(watermarkText, x, y, paint)
        return result
    }

    /**
     * Build the watermark time string.
     * If videoDate is available, combine it with the timestamp offset.
     * Otherwise, just use the offset from start.
     */
    private fun buildWatermarkText(timestampMs: Long, videoDate: Date?): String {
        return if (videoDate != null) {
            // Video has a known date — compute the actual datetime at this timestamp
            val actualTime = Date(videoDate.time + timestampMs)
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            sdf.format(actualTime)
        } else {
            // No video date — just show elapsed time
            val totalSec = timestampMs / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            val ms = timestampMs % 1000
            String.format(Locale.getDefault(), "%02d:%02d:%02d.%03d", h, m, s, ms)
        }
    }

    /**
     * Save all captured frames (with watermark) to gallery via MediaStore.
     * Returns the number of successfully saved images.
     */
    fun saveAll(watermarkPosition: WatermarkPosition): Int {
        var savedCount = 0

        for ((index, frame) in captures.withIndex()) {
            try {
                val watermarked = applyWatermark(frame.bitmap, watermarkPosition, frame.timestampMs, frame.videoDate)

                val filename = "screenshot_${System.currentTimeMillis()}_${index}.png"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                ) ?: continue

                context.contentResolver.openOutputStream(uri)?.use { os: OutputStream ->
                    watermarked.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, contentValues, null, null)
                }

                savedCount++
            } catch (e: Exception) {
                // Skip failed saves
            }
        }

        return savedCount
    }

    /**
     * Clear all captures.
     */
    fun clearAll() {
        captures.clear()
    }

    /**
     * Remove a capture at the given index.
     */
    fun removeAt(index: Int) {
        if (index in captures.indices) {
            captures.removeAt(index)
        }
    }

    /**
     * Get capture at index.
     */
    fun getCapture(index: Int): CapturedFrame? {
        return captures.getOrNull(index)
    }
}
