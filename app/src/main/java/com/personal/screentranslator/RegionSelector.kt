package com.personal.screentranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Полноэкранный слой: пальцем обводится область, которую нужно переводить
 * (обычно окно диалога). Результат — прямоугольник в координатах экрана.
 */
class RegionSelector(
    private val context: Context,
    private val wm: WindowManager,
) {
    sealed interface Result {
        data class Selected(val box: Box) : Result
        data object WholeScreen : Result
        data object Cancelled : Result
    }

    private var root: View? = null

    val isShowing get() = root != null

    fun show(initial: Box?, onResult: (Result) -> Unit) {
        dismiss()
        val canvasView = SelectionView(context, initial)
        val frame = FrameLayout(context)
        frame.addView(canvasView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = context.dpi(12f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = context.dp(14f)
                setColor(0xF21E2A3A.toInt())
            }
        }
        panel.addView(TextView(context).apply {
            text = "Обведите пальцем окно с текстом"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        })
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(0xFF3DDC84.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val p = context.dpi(12f)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Отмена") { dismiss(); onResult(Result.Cancelled) })
        buttons.addView(button("Весь экран") { dismiss(); onResult(Result.WholeScreen) })
        buttons.addView(button("Готово") {
            val box = canvasView.selection()
            if (box == null || box.width < context.dpi(40f) || box.height < context.dpi(20f)) {
                canvasView.flashHint()
            } else {
                dismiss()
                onResult(Result.Selected(box))
            }
        })
        panel.addView(buttons)

        frame.addView(
            panel,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = context.dpi(36f)
            }
        )

        val params = overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, touchable = true)
        if (wm.addSafely(frame, params)) root = frame
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        wm.removeSafely(r)
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class SelectionView(context: Context, initial: Box?) : View(context) {
        // Координаты экрана (rawX/rawY), рисуем со сдвигом окна.
        private var startX = 0f
        private var startY = 0f
        private var rect: RectF? = initial?.let {
            RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        }
        private val loc = IntArray(2)
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.dp(2.5f)
            color = 0xFF3DDC84.toInt()
        }
        fun selection(): Box? = rect?.let { Box(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }

        /** Область слишком маленькая — мигнуть рамкой красным. */
        fun flashHint() {
            border.color = 0xFFFF5252.toInt()
            invalidate()
            postDelayed({
                border.color = 0xFF3DDC84.toInt()
                invalidate()
            }, 600)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    rect = RectF(startX, startY, startX, startY)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    rect = RectF(min(startX, e.rawX), min(startY, e.rawY), max(startX, e.rawX), max(startY, e.rawY))
                }
            }
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            val r = rect?.let { RectF(it).apply { offset(-loc[0].toFloat(), -loc[1].toFloat()) } }
            val w = width.toFloat()
            val h = height.toFloat()
            if (r == null || r.isEmpty) {
                canvas.drawRect(0f, 0f, w, h, dim)
                return
            }
            // Затемнить всё, кроме выбранной области.
            canvas.drawRect(0f, 0f, w, r.top, dim)
            canvas.drawRect(0f, r.bottom, w, h, dim)
            canvas.drawRect(0f, r.top, r.left, r.bottom, dim)
            canvas.drawRect(r.right, r.top, w, r.bottom, dim)
            canvas.drawRect(r, border)
        }
    }
}
