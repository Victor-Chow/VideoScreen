# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project

视频截图APP — Android app for opening videos, scrubbing with a zoomable timeline, OCR-reading timestamps from video frames, capturing screenshots with watermarks, and saving them.

## Commands

```bash
# Build
./gradlew assembleDebug

# Install on device
./gradlew installDebug

# Clean
./gradlew clean
```

## Tech Stack

- Kotlin, Min SDK 30, Target SDK 34
- ExoPlayer (Media3 1.2.1) for video playback
- ML Kit Text Recognition for OCR timestamp extraction
- XML layouts + ViewBinding
- Gradle Kotlin DSL

## Architecture

Single-activity app. Key classes in `com.screenshot.app`:

- **MainActivity** — Wires ExoPlayer, ZoomableSeekBar, screenshot/save controls, region overlay. Handles `ACTION_VIEW` intents.
- **ZoomableSeekBar** — Custom `View` with `ScaleGestureDetector` for pinch-to-zoom and `GestureDetector` for drag-to-seek.
- **ScreenshotManager** — Captures frames via `MediaMetadataRetriever`, runs OCR on captured frames, applies watermarks, saves to gallery via MediaStore.
- **OcrTimeRecognizer** — Crops time region from frame, runs ML Kit text recognition, parses timestamp strings (dashcam/CCTV formats).
- **RegionOverlayView** — Overlay for user to drag-select the time region on the video frame.
- **DeviceConfig / DeviceConfigStore** — Named device configs with watermark position, persisted to SharedPreferences.
- **WatermarkPosition** — Enum for watermark placement.

## Key Details

- Timestamps come from **OCR of the video frame**, not EXIF/metadata (EXIF is often wrong for dashcams)
- User sets a "time region" on the frame where the timestamp appears; OCR only reads that area
- Time region is persisted in SharedPreferences and per-device configs are saved separately
- OCR corrects common misreads: O→0, l→1, S→5, B→8
- Supports formats: `2024-03-15 14:30:25`, `2024/03/15 14:30:25`, `2024年03月15日`, `DD-MM-YYYY`, compact `YYYYMMDDHHMMSS`
- Screenshot capture + OCR runs on background thread
- Scoped storage (API 30+): saves via `MediaStore.Images` with `IS_PENDING` flag
