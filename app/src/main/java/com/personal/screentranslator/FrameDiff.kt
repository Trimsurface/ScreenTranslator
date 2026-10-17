package com.personal.screentranslator

import kotlin.math.abs

/**
 * Сравнение двух уменьшенных кадров области (яркость 0..255 на пиксель).
 * Новая буква в окне диалога меняет несколько пикселей миниатюры заметно,
 * а шум сжатия/сглаживания — слабо.
 */
object FrameDiff {
    const val THUMB_W = 96
    const val THUMB_H = 24

    private const val PIXEL_DELTA = 16
    private const val MIN_CHANGED = 2

    fun changed(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null || a.size != b.size) return true
        var n = 0
        for (i in a.indices) {
            if (abs(a[i] - b[i]) > PIXEL_DELTA && ++n >= MIN_CHANGED) return true
        }
        return false
    }

    fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
