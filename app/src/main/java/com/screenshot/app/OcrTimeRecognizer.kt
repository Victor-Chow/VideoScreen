package com.screenshot.app

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * OCR-based timestamp recognizer.
 * Crops a region from the video frame, runs text recognition,
 * and parses the result as a datetime.
 */
class OcrTimeRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Saved time region as fraction of frame (0..1). Persisted by caller. */
    var timeRegion: RectF = RectF(0.6f, 0.85f, 0.98f, 0.98f)

    /**
     * Recognize timestamp from a video frame bitmap.
     * Crops the timeRegion, runs OCR, and parses the text.
     * Returns the recognized Date, or null if not found.
     */
    fun recognizeSync(bitmap: Bitmap): Date? {
        val cropped = cropRegion(bitmap) ?: return null
        val text = recognizeTextSync(cropped)
        if (text.isNullOrBlank()) return null
        return parseTimestamp(text)
    }

    /**
     * Crop the configured region from the bitmap.
     */
    private fun cropRegion(bitmap: Bitmap): Bitmap? {
        val x = (timeRegion.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (timeRegion.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val w = ((timeRegion.right - timeRegion.left) * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val h = ((timeRegion.bottom - timeRegion.top) * bitmap.height).toInt().coerceIn(1, bitmap.height - y)

        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(bitmap, x, y, w, h)
    }

    /**
     * Run ML Kit text recognition synchronously (call from background thread).
     */
    private fun recognizeTextSync(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val task = recognizer.process(image)
            // Block until complete (we're on a background thread)
            var waited = 0
            while (!task.isComplete && waited < 10000) {
                Thread.sleep(50)
                waited += 50
            }
            if (task.isSuccessful) task.result?.text else null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Common timestamp patterns from dashcams, CCTV, etc.
        private val PATTERNS = listOf(
            // 2024-03-15 14:30:25 or 2024/03/15 14:30:25
            Pattern.compile("(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})\\s+(\\d{1,2}):(\\d{2}):(\\d{2})"),
            // 2024年03月15日 14:30:25
            Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2}):(\\d{2})"),
            // 15-03-2024 14:30:25
            Pattern.compile("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})\\s+(\\d{1,2}):(\\d{2}):(\\d{2})"),
            // 14:30:25 2024-03-15
            Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})\\s+(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"),
            // 20240315143025 (compact)
            Pattern.compile("(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})"),
            // 14:30:25 only (no date)
            Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})"),
        )

        /**
         * Try to parse a datetime from OCR text.
         * Tries multiple common formats used by dashcams and surveillance cameras.
         */
        fun parseTimestamp(text: String): Date? {
            // Clean up common OCR mistakes
            val cleaned = text
                .replace("O", "0")
                .replace("o", "0")
                .replace("l", "1")
                .replace("I", "1")
                .replace("S", "5")
                .replace("B", "8")
                .replace("\n", " ")
                .trim()

            for (pattern in PATTERNS) {
                val matcher = pattern.matcher(cleaned)
                if (matcher.find()) {
                    try {
                        val date = parseMatch(matcher)
                        if (date != null) return date
                    } catch (_: Exception) {}
                }
            }
            return null
        }

        private fun parseMatch(matcher: java.util.regex.Matcher): Date? {
            val groups = (1..matcher.groupCount()).map { matcher.group(it) ?: "" }

            return when {
                // YYYY-MM-DD HH:MM:SS (groups: year, month, day, hour, min, sec)
                groups.size == 6 && groups[0].length == 4 && groups[0].toInt() in 2000..2099 -> {
                    parseStrict(
                        "${groups[0]}-${groups[1].padStart(2, '0')}-${groups[2].padStart(2, '0')}",
                        "${groups[3].padStart(2, '0')}:${groups[4]}:${groups[5]}"
                    )
                }

                // DD-MM-YYYY HH:MM:SS
                groups.size == 6 && groups[2].length == 4 -> {
                    parseStrict(
                        "${groups[2]}-${groups[1].padStart(2, '0')}-${groups[0].padStart(2, '0')}",
                        "${groups[3].padStart(2, '0')}:${groups[4]}:${groups[5]}"
                    )
                }

                // HH:MM:SS YYYY-MM-DD
                groups.size == 6 && groups[3].length == 4 -> {
                    parseStrict(
                        "${groups[3]}-${groups[4].padStart(2, '0')}-${groups[5].padStart(2, '0')}",
                        "${groups[0].padStart(2, '0')}:${groups[1]}:${groups[2]}"
                    )
                }

                // HH:MM:SS only — use today's date
                groups.size == 3 -> {
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    parseStrict(today, "${groups[0].padStart(2, '0')}:${groups[1]}:${groups[2]}")
                }

                else -> null
            }
        }

        private fun parseStrict(datePart: String, timePart: String): Date? {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            fmt.isLenient = false
            return try {
                fmt.parse("$datePart $timePart")
            } catch (_: Exception) {
                null
            }
        }
    }
}
