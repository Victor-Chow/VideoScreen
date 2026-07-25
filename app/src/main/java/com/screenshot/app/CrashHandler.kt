package com.screenshot.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash handler. Saves crash stack trace to:
 * 1. SharedPreferences (for in-app display on next launch)
 * 2. Downloads/ScreenshotApp/ directory (visible in file manager)
 * Then kills the process.
 */
object CrashHandler {
    private const val PREFS_NAME = "crash"
    private const val KEY_CRASH = "last_crash"

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val trace = throwable.stackTraceToString()
            saveToPrefs(context, trace)
            saveToFile(context, trace)
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    private fun saveToPrefs(context: Context, trace: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_CRASH, trace)
                .commit()
        } catch (_: Throwable) {}
    }

    private fun saveToFile(context: Context, trace: String) {
        // Method 1: MediaStore (works on Android 11+, visible in file manager)
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "crash_$timestamp.txt"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(trace.toByteArray())
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    return // Success via MediaStore
                }
            }
        } catch (_: Throwable) {}

        // Method 2: Direct file (fallback for older Android)
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ScreenshotApp"
            )
            if (!dir.exists()) dir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "crash_$timestamp.txt")
            file.writeText(trace)
        } catch (_: Throwable) {}

        // Method 3: App-specific external storage (always works, no permission needed)
        try {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (baseDir != null) {
                val dir = File(baseDir, "crash")
                if (!dir.exists()) dir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val file = File(dir, "crash_$timestamp.txt")
                file.writeText(trace)
            }
        } catch (_: Throwable) {}
    }

    /** Read saved crash info. Does NOT clear it. */
    fun getCrash(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CRASH, null)
        } catch (_: Throwable) { null }
    }

    /** Clear saved crash info. Call only after user has seen it. */
    fun clearCrash(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_CRASH).commit()
        } catch (_: Throwable) {}
    }
}
