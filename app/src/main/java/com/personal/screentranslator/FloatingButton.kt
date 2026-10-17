package com.personal.screentranslator

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/** Перетаскиваемая круглая кнопка. Тап — перевести, долгий тап — меню. */
class FloatingButton(
    private val context: Context,
    private val wm: WindowManager,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTap()
        fun onLongPress()
        /** Касание где угодно вне кнопки (в игре) — чтобы убрать перевод. */
        fun onOutsideTouch()
        fun onMoved(x: Int, y: Int)
    }

    enum class AutoState { OFF, ACTIVE, PAUSED }

    private val size = context.dpi(52f)
    val view = ButtonView(context)
    private val params = overlayParams(size, size, touchable = true, watchOutside = true)
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressed = false

    private val longPress = Runnable {
        longPressed = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        callbacks.onLongPress()
    }

    fun attach(x: Int, y: Int) {
        val screen = context.realScreenSize()
        params.x = if (x < 0) screen.x - size - context.dpi(8f) else x
        params.y = if (y < 0) screen.y / 3 else y
        clamp(screen.x, screen.y)
        view.setOnTouchListener(touchListener)
        wm.addSafely(view, params)
    }

    fun detach() {
        handler.removeCallbacks(longPress)
        view.busy = false
        wm.removeSafely(view)
    }

    fun box(): Box = view.screenBox()

    fun setBusy(busy: Boolean) {
        view.busy = busy
    }

    fun setAuto(state: AutoState) {
        view.autoState = state
    }

    /** После поворота экрана — вернуть кнопку в пределы экрана. */
    fun keepOnScreen() {
        val screen = context.realScreenSize()
        clamp(screen.x, screen.y)
        if (view.isAttachedToWindow) runCatching { wm.updateViewLayout(view, params) }
    }

    private fun clamp(w: Int, h: Int) {
        params.x = params.x.coerceIn(0, (w - size).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (h - size).coerceAtLeast(0))
    }

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                callbacks.onOutsideTouch()
                true
            }
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                longPressed = false
                handler.postDelayed(longPress, longPressTimeout)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && !longPressed && hypot(dx, dy) > slop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    val screen = context.realScreenSize()
                    params.x = (startX + dx).toInt()
                    params.y = (startY + dy).toInt()
                    clamp(screen.x, screen.y)
                    runCatching { wm.updateViewLayout(view, params) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                when {
                    dragging -> callbacks.onMoved(params.x, params.y)
                    !longPressed -> callbacks.onTap()
                }
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) callbacks.onMoved(params.x, params.y)
                dragging = false
                true
            }
            else -> false
        }
    }

    class ButtonView(context: Context) : View(context) {

        var busy = false
            set(v) {
                if (field == v) return
                field = v
                if (v) spinner.start() else spinner.cancel()
                invalidate()
            }

        var autoState = AutoState.OFF
            set(v) {
                field = v
                invalidate()
            }

        private var angle = 0f
        private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91E2A3A.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(3f)
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = context.sp(13f)
        }
        private val oval = RectF()

        init {
            alpha = 0.85f
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f - ring.strokeWidth
            canvas.drawCircle(cx, cy, r, fill)

            when (autoState) {
                AutoState.ACTIVE -> ring.color = 0xFF3DDC84.toInt()
                AutoState.PAUSED -> ring.color = 0xFF8A94A0.toInt()
                AutoState.OFF -> ring.color = Color.TRANSPARENT
            }
            if (autoState != AutoState.OFF) canvas.drawCircle(cx, cy, r, ring)

            if (busy) {
                oval.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(oval, angle, 90f, false, arc)
            }
            val y = cy - (label.descent() + label.ascent()) / 2f
            canvas.drawText(if (autoState == AutoState.ACTIVE) "АВТО" else "A→Я", cx, y, label)
        }

        override fun onDetachedFromWindow() {
            spinner.cancel()
            super.onDetachedFromWindow()
        }
    }
}
