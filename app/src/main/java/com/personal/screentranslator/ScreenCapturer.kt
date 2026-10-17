package com.personal.screentranslator

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CaptureException(val code: Int) : Exception("takeScreenshot error $code") {
    val isSecure get() = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
    val isTooFrequent get() = code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT

    val userMessage: String
        get() = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Это приложение запрещает снимки экрана"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Слишком часто — подождите секунду"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "Нет доступа к снимкам — выключите и снова включите сервис"
            else -> "Не удалось снять экран (код $code)"
        }
}

/** Снимок экрана через спец. возможности: без запроса разрешения каждую сессию. */
class ScreenCapturer(private val service: AccessibilityService) {

    private val mutex = Mutex()
    private var lastShotAt = 0L

    /** Программный ARGB_8888 bitmap всего экрана. Вызывающий отвечает за recycle(). */
    suspend fun capture(): Bitmap = mutex.withLock {
        // Система разрешает примерно 3 снимка в секунду — не упираемся в лимит.
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastShotAt)
        if (wait > 0) delay(wait)
        try {
            val result = takeShot()
            withContext(Dispatchers.Default) { toSoftwareBitmap(result) }
        } finally {
            lastShotAt = SystemClock.uptimeMillis()
        }
    }

    private suspend fun takeShot(): AccessibilityService.ScreenshotResult =
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) cont.resume(result) else result.hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resumeWithException(CaptureException(errorCode))
                    }
                }
            )
        }

    private fun toSoftwareBitmap(result: AccessibilityService.ScreenshotResult): Bitmap {
        val buffer = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw CaptureException(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            return sw ?: throw CaptureException(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
        } finally {
            buffer.close()
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 350L
    }
}
