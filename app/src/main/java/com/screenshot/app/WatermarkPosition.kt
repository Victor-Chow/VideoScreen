package com.screenshot.app

enum class WatermarkPosition(val label: String) {
    TOP_LEFT("左上"),
    TOP_RIGHT("右上"),
    BOTTOM_LEFT("左下"),
    BOTTOM_RIGHT("右下"),
    NONE("无水印");

    companion object {
        fun fromIndex(index: Int): WatermarkPosition {
            return entries.getOrElse(index) { BOTTOM_RIGHT }
        }
    }
}
