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
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var videoPath: String? = null
    private var videoDate: Date? = null
    private var pfd: ParcelFileDescriptor? = null

    private val screenshotManager = ScreenshotManager(this)
    private val deviceConfigStore = DeviceConfigStore(this)
    private var deviceConfigs = listOf<DeviceConfig>()
    private var updateTimer: Timer? = null

    private val openVideoLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { openVideo(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupControls()
        loadDeviceConfigs()
        handleIntent(intent)
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
                        val duration = duration
                        if (duration > 0) {
                            binding.seekBar.durationMs = duration
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
    }

    // --- Device Config ---

    private fun loadDeviceConfigs() {
        deviceConfigs = deviceConfigStore.loadAll()
        refreshDeviceSpinner()

        // Restore selected config
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

        val dialog = AlertDialog.Builder(this)
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
            .create()

        dialog.show()
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

    /** Get the watermark position from the currently selected device config. */
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
        videoDate = null
        pfd?.safeClose()
        pfd = null

        // Try to get file path for MediaMetadataRetriever
        videoPath = getFilePath(uri)

        // Try to get video date from MediaStore
        videoDate = getVideoDate(uri)

        // If no file path, open FD for MediaMetadataRetriever
        if (videoPath == null) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                // Will try other methods
            }
        }

        // Set up ExoPlayer
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

        val projection = arrayOf(MediaStore.Video.Media.DATA)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val colIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                return cursor.getString(colIndex)
            }
        }
        return null
    }

    private fun getVideoDate(uri: Uri): Date? {
        val projection = arrayOf(MediaStore.Video.Media.DATE_TAKEN)
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                    val dateTaken = cursor.getLong(colIndex)
                    if (dateTaken > 0) {
                        return Date(dateTaken)
                    }
                }
            }
        } catch (_: Exception) {}

        val projection2 = arrayOf(MediaStore.Video.Media.DATE_MODIFIED)
        try {
            contentResolver.query(uri, projection2, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val dateModified = cursor.getLong(colIndex)
                    if (dateModified > 0) {
                        return Date(dateModified * 1000)
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // --- Screenshot ---

    private fun captureScreenshot() {
        val pos = player?.currentPosition ?: return
        if (pos <= 0 && player?.duration == null) {
            Toast.makeText(this, R.string.no_video, Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val bitmap = if (videoPath != null) {
                screenshotManager.captureFrame(videoPath, pos, videoDate)
            } else {
                screenshotManager.captureFrameFromFd(pfd, pos, videoDate)
            }

            if (bitmap != null) {
                runOnUiThread {
                    addThumbnail(bitmap, screenshotManager.captureCount - 1)
                    Toast.makeText(this, "已截图 (${screenshotManager.captureCount})", Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "截图失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun addThumbnail(bitmap: Bitmap, index: Int) {
        val size = resources.getDimensionPixelSize(android.R.dimen.thumbnail_width).coerceAtLeast(80)
        val thumbnail = Bitmap.createScaledBitmap(bitmap, size, size * bitmap.height / bitmap.width, true)

        val iv = ImageView(this).apply {
            setImageBitmap(thumbnail)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = 8
            }
            setPadding(2, 2, 2, 2)
            setBackgroundColor(getColor(R.color.thumbnail_border))
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
        updateTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        updateSeekBar()
                    }
                }
            }, 0, 50)
        }
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

    // --- Extension ---

    private fun ParcelFileDescriptor?.safeClose() {
        try { this?.close() } catch (_: Exception) {}
    }
}
