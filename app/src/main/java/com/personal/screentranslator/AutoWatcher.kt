package com.personal.screentranslator

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Авто-режим для новелл: раз в ~секунду снимает выбранную область, сравнивает
 * уменьшенный кадр с прошлым и переводит, когда реплика сменилась и «допечаталась».
 */
class AutoWatcher(private val host: Host) {

    interface Host {
        /** Область в координатах экрана для кадра такого размера, или null. */
        fun regionFor(screenW: Int, screenH: Int): Box?
        fun intervalMs(): Long
        /** Пауза: ушли из игры, экран выключен, идёт ручной перевод. */
        fun isPaused(): Boolean
        suspend fun captureForRegion(): Bitmap
        suspend fun recognize(crop: Bitmap, dx: Int, dy: Int): List<Segment>
        suspend fun translate(text: String): String
        fun showPanel(text: String, region: Box, screenW: Int, screenH: Int)
        fun hidePanel()
        /** Ошибка, после которой продолжать бессмысленно (нет языкового пакета и т.п.). */
        fun onFatal(message: String)
    }

    private var job: Job? = null
    @Volatile
    private var forceNext = false

    val isRunning get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        forceNext = true
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Тап по кнопке в авто-режиме — перевести прямо сейчас, даже если кадр не менялся. */
    fun requestNow() {
        forceNext = true
    }

    private suspend fun loop() {
        var prevThumb: IntArray? = null
        var processedThumb: IntArray? = null
        var lastText: String? = null
        var changingTicks = 0
        var wasPaused = false

        while (currentCoroutineContext().isActive) {
            delay(if (changingTicks > 0 || forceNext) FAST_MS else host.intervalMs())

            if (host.isPaused()) {
                if (!wasPaused) host.hidePanel()
                wasPaused = true
                continue
            }
            if (wasPaused) {
                wasPaused = false
                forceNext = true
            }

            val full = try {
                host.captureForRegion()
            } catch (e: CaptureException) {
                if (e.isSecure) host.onFatal(e.userMessage)
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "capture failed", e)
                continue
            }

            var crop: Bitmap? = null
            try {
                val region = host.regionFor(full.width, full.height) ?: continue
                crop = TranslationPipeline.crop(full, region)
                val thumb = thumbnail(crop)
                val force = forceNext
                forceNext = false

                if (!force) {
                    if (prevThumb != null && FrameDiff.changed(prevThumb, thumb) && changingTicks < MAX_CHANGING_TICKS) {
                        // Текст ещё печатается или идёт анимация — ждём, пока кадр устоится.
                        prevThumb = thumb
                        changingTicks++
                        continue
                    }
                    prevThumb = thumb
                    if (changingTicks == 0 && processedThumb != null && !FrameDiff.changed(processedThumb, thumb)) continue
                }
                changingTicks = 0
                prevThumb = thumb
                processedThumb = thumb

                val segments = host.recognize(crop, region.left, region.top)
                val text = segments.joinToString("\n") { it.text }
                if (text.isBlank()) {
                    lastText = null
                    host.hidePanel()
                    continue
                }
                if (!force && text == lastText) continue
                lastText = text

                val translated = segments.map { host.translate(it.text) }.joinToString("\n")
                host.showPanel(translated, region, full.width, full.height)
            } catch (e: MissingModelException) {
                host.onFatal(e.message ?: "Нет языкового пакета")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "auto translate failed", e)
            } finally {
                if (crop != null && crop !== full) crop.recycle()
                full.recycle()
            }
        }
    }

    private fun thumbnail(bmp: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bmp, FrameDiff.THUMB_W, FrameDiff.THUMB_H, true)
        val px = IntArray(FrameDiff.THUMB_W * FrameDiff.THUMB_H)
        small.getPixels(px, 0, FrameDiff.THUMB_W, 0, 0, FrameDiff.THUMB_W, FrameDiff.THUMB_H)
        if (small !== bmp) small.recycle()
        for (i in px.indices) px[i] = FrameDiff.luma(px[i])
        return px
    }

    companion object {
        private const val TAG = "AutoWatcher"
        private const val FAST_MS = 400L
        /** Если кадр меняется дольше ~2 с (анимированный фон) — всё равно распознаём. */
        private const val MAX_CHANGING_TICKS = 5
    }
}
