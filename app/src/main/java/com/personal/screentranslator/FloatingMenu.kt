package com.personal.screentranslator

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Мини-меню по долгому тапу. Закрывается касанием мимо. */
class FloatingMenu(
    private val context: Context,
    private val wm: WindowManager,
    private val onDismissed: () -> Unit,
) {
    data class Item(val label: String, val action: () -> Unit)

    private var root: View? = null

    val isShowing get() = root != null

    fun show(items: List<Item>, anchor: Box) {
        dismiss()
        val list = OutsideAwareLayout(context) { dismiss() }.apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.dpi(6f)
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                cornerRadius = context.dp(14f)
                setColor(0xF21E2A3A.toInt())
            }
        }
        for (item in items) {
            list.addView(TextView(context).apply {
                text = item.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(context.dpi(18f), context.dpi(12f), context.dpi(22f), context.dpi(12f))
                minWidth = context.dpi(200f)
                setOnClickListener {
                    dismiss()
                    item.action()
                }
            })
        }

        val screen = context.realScreenSize()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(screen.x, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(screen.y, View.MeasureSpec.AT_MOST),
        )
        val w = list.measuredWidth
        val h = list.measuredHeight
        val gap = context.dpi(8f)
        val params = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            touchable = true, watchOutside = true,
        )
        params.x = if (anchor.right + gap + w <= screen.x) anchor.right + gap else (anchor.left - gap - w).coerceAtLeast(0)
        params.y = anchor.top.coerceIn(0, (screen.y - h).coerceAtLeast(0))
        if (wm.addSafely(list, params)) root = list
    }

    fun dismiss() {
        val r = root ?: return
        root = null
        wm.removeSafely(r)
        onDismissed()
    }

    /** Ловит касание вне окна (ACTION_OUTSIDE) раньше, чем его увидят пункты меню. */
    private class OutsideAwareLayout(context: Context, val onOutside: () -> Unit) : LinearLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }
}
