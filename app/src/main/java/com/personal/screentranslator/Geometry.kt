package com.personal.screentranslator

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Прямоугольник в пикселях экрана. Свой класс, чтобы логику можно было тестировать без Android. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    /** Пересечение по горизонтали в пикселях (0, если не пересекаются). */
    fun horizontalOverlap(o: Box) = max(0, min(right, o.right) - max(left, o.left))

    fun clampTo(w: Int, h: Int) = Box(
        left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h)
    )
}

/** Область в долях экрана (0..1), чтобы переживать смену разрешения. */
data class NormRect(val l: Float, val t: Float, val r: Float, val b: Float) {

    fun toBox(w: Int, h: Int): Box =
        Box((l * w).roundToInt(), (t * h).roundToInt(), (r * w).roundToInt(), (b * h).roundToInt())
            .clampTo(w, h)

    fun encode(): String = "$l,$t,$r,$b"

    companion object {
        fun of(box: Box, w: Int, h: Int): NormRect = NormRect(
            box.left.toFloat() / w, box.top.toFloat() / h,
            box.right.toFloat() / w, box.bottom.toFloat() / h
        )

        fun decode(s: String?): NormRect? {
            val p = s?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: return null
            if (p.size != 4 || p[2] <= p[0] || p[3] <= p[1]) return null
            return NormRect(p[0], p[1], p[2], p[3])
        }
    }
}
