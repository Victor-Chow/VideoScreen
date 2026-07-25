# AGENTS.md

This file provides guidance to AI coding agents when working with code in this repository.

## Project

视频截图APP — Android app for opening videos, scrubbing with a zoomable timeline, capturing frame screenshots with timestamp watermarks, and saving them.

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
- XML layouts + ViewBinding
- Gradle Kotlin DSL

## Architecture

Single-activity app. Key classes in `com.screenshot.app`:

- **MainActivity** — Wires ExoPlayer, ZoomableSeekBar, screenshot/save controls. Handles `ACTION_VIEW` intents from file managers.
- **ZoomableSeekBar** — Custom `View` with `ScaleGestureDetector` for pinch-to-zoom on the time axis and `GestureDetector` for drag-to-seek. Draws timeline bar, tick marks, position indicator, and time labels.
- **ScreenshotManager** — Captures frames via `MediaMetadataRetriever`, applies timestamp watermarks (Canvas + Paint), saves to gallery via MediaStore. Stores captures as `CapturedFrame(bitmap, timestampMs, videoDate)`.
- **WatermarkPosition** — Enum for watermark placement (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NONE).

## Key Details

- Screenshot capture runs on a background thread; UI updates on main thread
- `MediaMetadataRetriever` uses file path when available, falls back to `ParcelFileDescriptor` for content:// URIs
- Watermark text uses video's `DATE_TAKEN`/`DATE_MODIFIED` from MediaStore combined with timestamp offset for real datetime
- Scoped storage (API 30+): saves via `MediaStore.Images` with `IS_PENDING` flag
- Thumbnails in bottom strip: long-press to delete individual captures
