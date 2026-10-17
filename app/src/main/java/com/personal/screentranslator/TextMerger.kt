package com.personal.screentranslator

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrLine(val text: String, val box: Box)

data class OcrBlock(val lines: List<OcrLine>) {
    val box: Box get() = lines.map { it.box }.reduce { a, b -> a.union(b) }
    fun offset(dx: Int, dy: Int) = OcrBlock(lines.map { it.copy(box = it.box.offset(dx, dy)) })
}

/** Кусок текста, который переводится целиком и рисуется одной плашкой. */
data class Segment(
    val text: String,
    val box: Box,
    val lineCount: Int,
    /** Типичная высота строки оригинала — от неё подбирается шрифт перевода. */
    val lineHeight: Int,
)

/**
 * Склейка строк OCR в абзацы. Построчный перевод ломает предложения,
 * поэтому строки одного блока соединяются, а соседние куски одного абзаца,
 * которые OCR разнёс по разным блокам, собираются обратно.
 */
object TextMerger {

    fun merge(blocks: List<OcrBlock>, cjk: Boolean): List<Segment> {
        val groups = mergeBlocks(blocks.filter { it.lines.isNotEmpty() })
        return groups.map { lines -> toSegment(lines, cjk) }
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<Segment> { it.box.top }.thenBy { it.box.left })
    }

    private fun mergeBlocks(blocks: List<OcrBlock>): List<List<OcrLine>> {
        val sorted = blocks.sortedWith(compareBy<OcrBlock> { it.box.top }.thenBy { it.box.left })
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (block in sorted) {
            val target = groups.firstOrNull { canAppend(it, block) }
            if (target != null) target += block.lines else groups += block.lines.toMutableList()
        }
        return groups
    }

    /** Блок продолжает абзац: сразу под ним, тот же размер шрифта, та же колонка. */
    private fun canAppend(group: List<OcrLine>, block: OcrBlock): Boolean {
        val last = group.last()
        val first = block.lines.first()
        val lhA = medianHeight(group)
        val lhB = medianHeight(block.lines)
        if (lhA <= 0 || lhB <= 0) return false
        if (max(lhA, lhB).toFloat() / min(lhA, lhB) > 1.35f) return false

        val lh = (lhA + lhB) / 2f
        val gap = first.box.top - last.box.bottom
        if (gap < -0.3f * lh || gap > 0.7f * lh) return false

        val groupBox = group.map { it.box }.reduce { a, b -> a.union(b) }
        val blockBox = block.box
        val overlap = groupBox.horizontalOverlap(blockBox)
        if (overlap < 0.5f * min(groupBox.width, blockBox.width)) return false
        val sameLeft = abs(groupBox.left - blockBox.left) < 2 * lh
        val sameCenter = abs((groupBox.left + groupBox.right) - (blockBox.left + blockBox.right)) / 2f < 2 * lh
        if (!sameLeft && !sameCenter) return false

        // Короткая одиночная строка сверху — это имя говорящего или заголовок, не склеиваем.
        if (group.size == 1 && looksLikeLabel(group[0].text)) return false
        return true
    }

    private fun looksLikeLabel(text: String): Boolean {
        val t = text.trim()
        val words = t.split(Regex("\\s+")).size
        return words <= 3 && t.length <= 25 && t.lastOrNull()?.let { it in ".,!?…:;\"»”" } != true
    }

    private fun toSegment(lines: List<OcrLine>, cjk: Boolean): Segment {
        val ordered = lines.sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
        val text = joinLines(ordered.map { it.text }, cjk)
        val box = ordered.map { it.box }.reduce { a, b -> a.union(b) }
        return Segment(text, box, ordered.size, medianHeight(ordered))
    }

    fun joinLines(lines: List<String>, cjk: Boolean): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val prevIsHyphen = sb.last() == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter()
            when {
                cjk -> sb.append(line)
                prevIsHyphen && line.first().isLowerCase() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun medianHeight(lines: List<OcrLine>): Int {
        val h = lines.map { it.box.height }.sorted()
        return if (h.isEmpty()) 0 else h[h.size / 2]
    }
}
