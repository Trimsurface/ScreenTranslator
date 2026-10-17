package com.personal.screentranslator

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Параметры окна поверх всех приложений от имени сервиса спец. возможностей.
 * TYPE_ACCESSIBILITY_OVERLAY не требует разрешения «поверх других окон», а касания
 * сквозь такие окна система пропускает (это доверенный тип окна).
 */
fun overlayParams(
    width: Int,
    height: Int,
    touchable: Boolean,
    watchOutside: Boolean = false,
): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    return WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }
}

fun WindowManager.addSafely(view: View, params: WindowManager.LayoutParams): Boolean =
    runCatching { addView(view, params) }.isSuccess

fun WindowManager.removeSafely(view: View) {
    if (view.isAttachedToWindow) runCatching { removeViewImmediate(view) }
}

/** Полный размер экрана в пикселях (со статус-баром и вырезом) — как у снимка экрана. */
fun Context.realScreenSize(): Point {
    val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val p = Point()
    @Suppress("DEPRECATION")
    display.getRealSize(p)
    return p
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

/** Положение view на экране как Box. */
fun View.screenBox(): Box {
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    return Box(loc[0], loc[1], loc[0] + width, loc[1] + height)
}
