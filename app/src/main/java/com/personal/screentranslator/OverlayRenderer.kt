package com.personal.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Прозрачное окно на весь экран, в котором рисуются плашки перевода.
 *
 * Обычно касания проходят сквозь него в игру. Но пока показаны плашки ручного перевода
 * (и включено «убирать касанием»), окно само ловит касание в любом месте экрана:
 * перевод исчезает, а игра это касание не получает — можно спокойно перечитать оригинал.
 * Панель авто-режима касания не ловит никогда.
 */
class OverlayRenderer(
    private val context: Context,
    private val wm: WindowManager,
    private val dismissOnTouch: () -> Boolean,
) {

    val view = OverlayView(context)
    private val params = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = false)
    private var touchable = false
    /** Палец ещё на экране после касания, убравшего перевод: до отпускания жест наш. */
    private var gestureActive = false

    init {
        view.onTouchDown = {
            gestureActive = true
            view.clear() // окно не трогаем до конца жеста — остаток касания тоже не уйдёт в игру
        }
        view.onTouchEnd = {
            gestureActive = false
            syncTouchable()
        }
    }

    fun attach() {
        wm.addSafely(view, params)
    }

    fun detach() = wm.removeSafely(view)

    val kind get() = view.kind
    val hasContent get() = view.kind != OverlayView.Kind.NONE

    fun intersects(area: Box) = view.contentBoxes().any { it.intersects(area) }

    fun clear() {
        view.clear()
        syncTouchable()
    }

    fun showBubbles(items: List<Pair<Segment, String>>, screenW: Int, screenH: Int) {
        view.showBubbles(items, screenW, screenH)
        syncTouchable()
    }

    fun showDebug(segments: List<Segment>, screenW: Int, screenH: Int) {
        view.showDebug(segments, screenW, screenH)
        syncTouchable()
    }

    fun showPanel(text: String, region: Box, position: PanelPosition, textSp: Int, screenW: Int, screenH: Int) {
        view.showPanel(text, region, position, textSp, screenW, screenH)
        syncTouchable()
    }

    private fun syncTouchable() {
        val manualShown = view.kind == OverlayView.Kind.BUBBLES || view.kind == OverlayView.Kind.DEBUG
        val want = gestureActive || (manualShown && dismissOnTouch())
        if (want == touchable) return
        touchable = want
        params.flags = if (want) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (view.isAttachedToWindow) runCatching { wm.updateViewLayout(view, params) }
    }
}

class OverlayView(context: Context) : View(context) {

    enum class Kind { NONE, BUBBLES, DEBUG, PANEL }

    private class Placed(val rect: RectF, val layout: StaticLayout?, val pad: Float, val fill: Paint, val stroke: Paint?)

    var kind = Kind.NONE
        private set
    private var placed: List<Placed> = emptyList()
    private val loc = IntArray(2)

    /** Касания приходят сюда, только когда окно сделано «касаемым» (см. OverlayRenderer). */
    var onTouchDown: (() -> Unit)? = null
    var onTouchEnd: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onTouchDown?.invoke()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onTouchEnd?.invoke()
        }
        return true // касание съедено — до игры не доходит
    }

    private val ctx = context
    private val radius = ctx.dp(6f)
    private val baseText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101418.toInt() }
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2101418.toInt() }
    private val debugFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300E676 }
    private val debugStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2f)
        color = 0xFF00E676.toInt()
    }

    fun clear() {
        if (kind == Kind.NONE) return
        kind = Kind.NONE
        placed = emptyList()
        invalidate()
    }

    fun contentBoxes(): List<Box> = placed.map {
        Box(it.rect.left.toInt(), it.rect.top.toInt(), it.rect.right.toInt() + 1, it.rect.bottom.toInt() + 1)
    }

    /** Плашки поверх оригинала: шрифт подбирается так, чтобы перевод влез в рамку блока. */
    fun showBubbles(items: List<Pair<Segment, String>>, screenW: Int, screenH: Int) {
        val pad = ctx.dp(3f)
        val minPx = ctx.sp(10f)
        val maxPx = ctx.sp(30f)
        placed = items.map { (seg, text) ->
            val w = max(seg.box.width.toFloat() + 2 * pad, ctx.dp(48f))
            val h = seg.box.height + 2 * pad
            val left = (seg.box.left - pad).coerceIn(0f, max(0f, screenW - w))
            val innerW = (w - 2 * pad).toInt().coerceAtLeast(1)

            var size = (seg.lineHeight * 0.8f).coerceIn(minPx, maxPx)
            var layout = makeLayout(text, innerW, size)
            while (layout.height > h - 2 * pad && size > minPx) {
                size = max(minPx, size * 0.92f)
                layout = makeLayout(text, innerW, size)
            }
            val height = max(h, layout.height + 2 * pad)
            var top = seg.box.top - pad
            if (top + height > screenH) top = max(0f, screenH - height)
            Placed(RectF(left, top, left + w, top + height), layout, pad, bubbleFill, null)
        }
        kind = Kind.BUBBLES
        invalidate()
    }

    /** Режим проверки распознавания: только рамки найденного текста. */
    fun showDebug(segments: List<Segment>, screenW: Int, screenH: Int) {
        val pad = ctx.dp(2f)
        placed = segments.map { seg ->
            val r = RectF(seg.box.left - pad, seg.box.top - pad, seg.box.right + pad, seg.box.bottom + pad)
            r.intersect(0f, 0f, screenW.toFloat(), screenH.toFloat())
            Placed(r, null, 0f, debugFill, debugStroke)
        }
        kind = Kind.DEBUG
        invalidate()
    }

    /** Панель рядом с областью (авто-режим) — не перекрывает область, поэтому не мешает снимкам. */
    fun showPanel(text: String, region: Box, position: PanelPosition, textSp: Int, screenW: Int, screenH: Int) {
        val pad = ctx.dp(10f)
        val gap = ctx.dp(6f)
        val margin = ctx.dp(8f)
        val w = min(max(region.width.toFloat(), ctx.dp(240f)), screenW - 2 * margin)
        val left = region.left.toFloat().coerceIn(margin, max(margin, screenW - margin - w))
        val layout = makeLayout(text, (w - 2 * pad).toInt().coerceAtLeast(1), ctx.sp(textSp.toFloat()))
        val h = layout.height + 2 * pad

        val above = region.top - gap - h
        val below = region.bottom + gap
        val top = when (position) {
            PanelPosition.ABOVE -> if (above >= 0) above else if (below + h <= screenH) below else margin
            PanelPosition.BELOW -> if (below + h <= screenH) below else if (above >= 0) above else margin
            PanelPosition.TOP -> margin
        }
        placed = listOf(Placed(RectF(left, top, left + w, top + h), layout, pad, panelFill, null))
        kind = Kind.PANEL
        invalidate()
    }

    private fun makeLayout(text: String, width: Int, sizePx: Float): StaticLayout {
        // У каждой раскладки своя кисть: StaticLayout хранит ссылку на неё.
        val paint = TextPaint(baseText).apply { textSize = sizePx }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
    }

    override fun onDraw(canvas: Canvas) {
        if (placed.isEmpty()) return
        // Окно может быть сдвинуто системой (вырез камеры, панели) — рисуем в координатах экрана.
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (p in placed) {
            canvas.drawRoundRect(p.rect, radius, radius, p.fill)
            p.stroke?.let { canvas.drawRoundRect(p.rect, radius, radius, it) }
            p.layout?.let {
                canvas.save()
                canvas.translate(p.rect.left + p.pad, p.rect.top + (p.rect.height() - it.height) / 2f)
                it.draw(canvas)
                canvas.restore()
            }
        }
        canvas.restore()
    }
}
