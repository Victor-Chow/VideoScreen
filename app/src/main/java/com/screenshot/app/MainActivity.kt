package com.screenshot.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.screenshot.app.databinding.ActivityMainBinding
import com.screenshot.app.databinding.DialogDeviceConfigBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var deviceConfigStore: DeviceConfigStore
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var videoPath: String? = null
    private var pfd: ParcelFileDescriptor? = null
    private var deviceConfigs = listOf<DeviceConfig>()
    private var updateTimer: Timer? = null
    private var isRegionSelectMode = false

    private val openVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { openVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for crash from previous launch FIRST
        val lastCrash = CrashHandler.getCrash(this)
        if (lastCrash != null) {
            // Don't clear crash info until user confirms — so it persists even if showErrorScreen fails
            showErrorScreen("上次崩溃信息（点击返回关闭）:\n\n$lastCrash") {
                CrashHandler.clearCrash(this)
                // Restart the activity normally
                recreate()
            }
            return
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            screenshotManager = ScreenshotManager(applicationContext)
            deviceConfigStore = DeviceConfigStore(this)

            setupPlayer()
            setupControls()
            loadDeviceConfigs()
            loadOcrRegion()
            handleIntent(intent)
        } catch (e: Throwable) {
            showErrorScreen("初始化错误:\n\n${e.stackTraceToString()}")
        }
    }

    private fun showErrorScreen(message: String, onDismiss: (() -> Unit)? = null) {
        try {
            val sv = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this)
            tv.text = message
            tv.textSize = 12f
            tv.setPadding(32, 32, 32, 32)
            tv.setTextIsSelectable(true)
            sv.addView(tv)
            setContentView(sv)

            // Press back to dismiss
            if (onDismiss != null) {
                sv.isFocusableInTouchMode = true
                sv.requestFocus()
                sv.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                        onDismiss()
                        true
                    } else false
                }
            }
        } catch (e: Throwable) {
            // Last resort: write to system log
            android.util.Log.e("ScreenshotApp", "Failed to show error screen", e)
            android.util.Log.e("ScreenshotApp", "Original error: $message")
        }
    }

    private fun showFatalError(e: Exception) {
        android.util.Log.e("ScreenshotApp", "Fatal error in onCreate", e)
        try {
            val sv = android.widget.ScrollView(this)
            val tv = android.widget.TextView(this)
            tv.text = "初始化错误:\n\n${e.stackTraceToString()}"
            tv.textSize = 12f
            tv.setPadding(32, 32, 32, 32)
            tv.setTextIsSelectable(true)
            sv.addView(tv)
            setContentView(sv)
        } catch (_: Exception) {
            // If even error display fails, nothing we can do
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    // --- Player setup ---

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build().apply {
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                        val dur = duration
                        if (dur > 0) {
                            binding.seekBar.durationMs = dur
                        }
                    }
                }
            })
        }
        binding.playerView.player = player
    }

    // --- Controls ---

    private fun setupControls() {
        binding.btnOpen.setOnClickListener {
            openVideoLauncher.launch(arrayOf("video/*"))
        }

        binding.btnScreenshot.setOnClickListener {
            captureScreenshot()
        }

        binding.btnSave.setOnClickListener {
            saveScreenshots()
        }

        binding.seekBar.onSeekListener = { pos ->
            player?.seekTo(pos)
        }

        binding.btnAddDevice.setOnClickListener {
            showDeviceConfigDialog(addMode = true, existingConfig = null)
        }

        binding.btnDeleteDevice.setOnClickListener {
            deleteSelectedDevice()
        }

        binding.spinnerDevice.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position in deviceConfigs.indices) {
                    deviceConfigStore.setSelectedId(deviceConfigs[position].id)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnSetRegion.setOnClickListener {
            toggleRegionSelect()
        }

        binding.regionOverlay.onRegionChanged = { region ->
            screenshotManager.ocrRecognizer.timeRegion = region
            saveOcrRegion()
        }
    }

    // --- Region select mode ---

    private fun toggleRegionSelect() {
        isRegionSelectMode = !isRegionSelectMode
        binding.regionOverlay.isSelecting = isRegionSelectMode
        binding.regionOverlay.region = screenshotManager.ocrRecognizer.timeRegion
        binding.regionOverlay.invalidate()

        if (isRegionSelectMode) {
            binding.btnSetRegion.text = "完成"
            player?.pause()
            Toast.makeText(this, "拖动橙色框调整时间水印识别区域，再点完成", Toast.LENGTH_LONG).show()
        } else {
            binding.btnSetRegion.text = getString(R.string.set_time_region)
        }
    }

    // --- OCR Region persistence ---

    private fun saveOcrRegion() {
        val r = screenshotManager.ocrRecognizer.timeRegion
        getSharedPreferences("ocr_config", MODE_PRIVATE).edit()
            .putFloat("region_left", r.left)
            .putFloat("region_top", r.top)
            .putFloat("region_right", r.right)
            .putFloat("region_bottom", r.bottom)
            .apply()
    }

    private fun loadOcrRegion() {
        val prefs = getSharedPreferences("ocr_config", MODE_PRIVATE)
        screenshotManager.ocrRecognizer.timeRegion = android.graphics.RectF(
            prefs.getFloat("region_left", 0.6f),
            prefs.getFloat("region_top", 0.85f),
            prefs.getFloat("region_right", 0.98f),
            prefs.getFloat("region_bottom", 0.98f)
        )
    }

    // --- Device Config ---

    private fun loadDeviceConfigs() {
        deviceConfigs = deviceConfigStore.loadAll()
        refreshDeviceSpinner()

        val selectedId = deviceConfigStore.getSelectedId()
        if (selectedId != -1L) {
            val idx = deviceConfigs.indexOfFirst { it.id == selectedId }
            if (idx >= 0) {
                binding.spinnerDevice.setSelection(idx)
            }
        }
    }

    private fun refreshDeviceSpinner() {
        val labels = if (deviceConfigs.isEmpty()) {
            listOf(getString(R.string.device_default))
        } else {
            deviceConfigs.map { "${it.name}（${it.position.label}）" }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevice.adapter = adapter
    }

    private fun showDeviceConfigDialog(addMode: Boolean, existingConfig: DeviceConfig?) {
        val dialogBinding = DialogDeviceConfigBinding.inflate(layoutInflater)

        dialogBinding.tvDialogTitle.text = if (addMode) {
            getString(R.string.add_device)
        } else {
            getString(R.string.edit_device)
        }

        if (existingConfig != null) {
            dialogBinding.etDeviceName.setText(existingConfig.name)
            dialogBinding.spinnerWatermarkPos.setSelection(existingConfig.position.ordinal)
        }

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(if (addMode) "添加" else "保存") { _, _ ->
                val name = dialogBinding.etDeviceName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val posIndex = dialogBinding.spinnerWatermarkPos.selectedItemPosition
                val position = WatermarkPosition.fromIndex(posIndex)

                if (addMode) {
                    val config = deviceConfigStore.add(name, position)
                    deviceConfigStore.setSelectedId(config.id)
                } else if (existingConfig != null) {
                    deviceConfigStore.update(existingConfig.id, name, position)
                }

                loadDeviceConfigs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedDevice() {
        val pos = binding.spinnerDevice.selectedItemPosition
        if (deviceConfigs.isEmpty() || pos !in deviceConfigs.indices) {
            Toast.makeText(this, R.string.no_device_selected, Toast.LENGTH_SHORT).show()
            return
        }

        val config = deviceConfigs[pos]
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.confirm_delete, config.name))
            .setPositiveButton("删除") { _, _ ->
                deviceConfigStore.remove(config.id)
                loadDeviceConfigs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getCurrentWatermarkPosition(): WatermarkPosition {
        val pos = binding.spinnerDevice.selectedItemPosition
        if (deviceConfigs.isEmpty() || pos !in deviceConfigs.indices) {
            return WatermarkPosition.BOTTOM_RIGHT
        }
        return deviceConfigs[pos].position
    }

    // --- Video loading ---

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { openVideo(it) }
            }
        }
    }

    private fun openVideo(uri: Uri) {
        videoUri = uri
        videoPath = null
        pfd?.safeClose()
        pfd = null

        videoPath = getFilePath(uri)

        if (videoPath == null) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {}
        }

        val mediaItem = MediaItem.fromUri(uri)
        player?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = false
        }

        binding.tvNoVideo.visibility = View.GONE
        binding.seekBar.resetZoom()
        screenshotManager.clearAll()
        clearThumbnails()
    }

    private fun getFilePath(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path
        }

        try {
            val projection = arrayOf(MediaStore.Video.Media.DATA)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    return cursor.getString(colIndex)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // --- Screenshot ---

    private fun captureScreenshot() {
        if (isRegionSelectMode) {
            Toast.makeText(this, "请先完成区域选择", Toast.LENGTH_SHORT).show()
            return
        }

        val pos = player?.currentPosition ?: return
        if (pos <= 0 && player?.duration == null) {
            Toast.makeText(this, R.string.no_video, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnScreenshot.isEnabled = false
        Thread {
            val bitmap = try {
                if (videoPath != null) {
                    screenshotManager.captureFrame(videoPath, pos)
                } else {
                    screenshotManager.captureFrameFromFd(pfd, pos)
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenshotApp", "Capture failed", e)
                null
            }

            val lastCapture = screenshotManager.getCapture(screenshotManager.captureCount - 1)

            runOnUiThread {
                binding.btnScreenshot.isEnabled = true
                if (bitmap != null) {
                    addThumbnail(bitmap, screenshotManager.captureCount - 1)

                    if (lastCapture?.ocrDate != null) {
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        binding.tvOcrTime.text = "识别时间: ${sdf.format(lastCapture.ocrDate)}"
                        binding.tvOcrTime.visibility = View.VISIBLE
                        Toast.makeText(this, "已截图 (${screenshotManager.captureCount}) OCR:${sdf.format(lastCapture.ocrDate)}", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.tvOcrTime.text = "未识别到时间水印，将使用播放时间"
                        binding.tvOcrTime.visibility = View.VISIBLE
                        Toast.makeText(this, "已截图 (${screenshotManager.captureCount}) 未识别到时间", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun addThumbnail(bitmap: Bitmap, index: Int) {
        val thumbnailSize = 80
        val thumbnail = Bitmap.createScaledBitmap(
            bitmap, thumbnailSize,
            (thumbnailSize * bitmap.height / bitmap.width.toFloat()).toInt(), true
        )

        val iv = ImageView(this).apply {
            setImageBitmap(thumbnail)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            lp.setMargins(0, 0, 8, 0)
            layoutParams = lp
            setPadding(2, 2, 2, 2)
            setBackgroundColor(0xFF4CAF50.toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP

            setOnLongClickListener {
                screenshotManager.removeAt(index)
                (parent as? LinearLayout)?.removeView(this)
                Toast.makeText(this@MainActivity, "已删除截图", Toast.LENGTH_SHORT).show()
                true
            }
        }

        binding.thumbnailStrip.addView(iv)
    }

    private fun clearThumbnails() {
        binding.thumbnailStrip.removeAllViews()
    }

    // --- Save ---

    private fun saveScreenshots() {
        if (screenshotManager.captureCount == 0) {
            Toast.makeText(this, R.string.no_screenshots, Toast.LENGTH_SHORT).show()
            return
        }

        val position = getCurrentWatermarkPosition()

        Thread {
            val count = screenshotManager.saveAll(position)
            runOnUiThread {
                if (count > 0) {
                    Toast.makeText(this, getString(R.string.saved, count), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // --- Timeline update loop ---

    override fun onStart() {
        super.onStart()
        startUpdateLoop()
    }

    override fun onStop() {
        super.onStop()
        stopUpdateLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        pfd?.safeClose()
        pfd = null
    }

    private fun startUpdateLoop() {
        stopUpdateLoop()
        updateTimer = Timer()
        updateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateSeekBar() }
            }
        }, 0, 50)
    }

    private fun stopUpdateLoop() {
        updateTimer?.cancel()
        updateTimer = null
    }

    private fun updateSeekBar() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration

        if (dur > 0) {
            binding.seekBar.durationMs = dur
            binding.seekBar.positionMs = pos
            binding.tvTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    private fun ParcelFileDescriptor?.safeClose() {
        try { this?.close() } catch (_: Exception) {}
    }
}
