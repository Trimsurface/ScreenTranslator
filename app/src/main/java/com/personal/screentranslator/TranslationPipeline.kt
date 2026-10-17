package com.personal.screentranslator

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException

/** Нет интернета для онлайн-перевода, а запасной офлайн невозможен — пакеты не скачаны. */
class NoInternetException :
    Exception("Нет интернета, а офлайн-пакеты не скачаны. Подключитесь к сети или скачайте пакеты в настройках")

/** OCR → склейка → фильтр → (кеш | перевод онлайн или офлайн) → словарь. */
class TranslationPipeline(private val app: App) {

    private val ocr = OcrEngine()

    /** Когда онлайн-перевод последний раз не удался — чтобы не ждать таймаут на каждой реплике. */
    @Volatile
    private var onlineFailedAt = 0L
    @Volatile
    private var pendingNotice: String? = null
    @Volatile
    private var lastNoticeAt = 0L

    /**
     * @param bitmap кадр (весь экран или вырезанная область)
     * @param dx,dy где этот кадр находится на экране — чтобы вернуть координаты экрана
     */
    suspend fun recognize(bitmap: Bitmap, dx: Int, dy: Int, lang: SourceLang): List<Segment> {
        val blocks = ocr.recognize(bitmap, lang).map { it.offset(dx, dy) }
        return TextMerger.merge(blocks, cjk = lang.cjk).filterNot { TextFilter.isJunk(it.text) }
    }

    suspend fun translate(text: String, lang: SourceLang): String {
        val normalized = if (lang.cjk) text else TextNormalizer.fixCaps(text)
        val prepared = app.dictionary.prepare(normalized)
        // В кеше — перевод текста с метками, словарь применяется после: правки словаря сразу работают.
        val raw = if (app.settings.value.onlineMode) translateOnline(prepared.text, lang)
        else translateOffline(prepared.text, lang)
        return app.dictionary.finish(raw, prepared)
    }

    /**
     * Сообщение для пользователя о том, что онлайн не сработал (не чаще раза в минуту).
     * Сервис забирает его после перевода и показывает тостом.
     */
    fun takeNotice(): String? = pendingNotice?.also { pendingNotice = null }

    private suspend fun translateOffline(text: String, lang: SourceLang): String {
        val key = lang.code + "|" + text
        return app.cache.get(key) ?: app.translator.translate(text, lang).also { remember(key, it) }
    }

    /** Google Переводчик; без интернета или при ошибке — офлайн-перевод на телефоне. */
    private suspend fun translateOnline(text: String, lang: SourceLang): String {
        // Онлайн-переводы кешируются отдельно: качество другое, не смешиваем с офлайновыми.
        val key = ONLINE_KEY_PREFIX + lang.code + "|" + text
        app.cache.get(key)?.let { return it }

        if (onlineAllowed()) {
            try {
                val result = app.onlineTranslator.translate(text, lang)
                onlineFailedAt = 0L
                remember(key, result)
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "online translate failed", e)
                onlineFailedAt = SystemClock.elapsedRealtime()
            }
        }

        return try {
            translateOffline(text, lang).also { notice("Онлайн-перевод недоступен — перевожу офлайн") }
        } catch (e: MissingModelException) {
            notice(NoInternetException().message!!)
            throw NoInternetException()
        }
    }

    private fun onlineAllowed(): Boolean {
        if (!SystemChecks.hasInternet(app)) return false
        val failedAt = onlineFailedAt
        return failedAt == 0L || SystemClock.elapsedRealtime() - failedAt > RETRY_ONLINE_MS
    }

    private fun remember(key: String, value: String) {
        if (app.cache.put(key, value)) app.saveCacheAsync()
    }

    private fun notice(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastNoticeAt != 0L && now - lastNoticeAt < NOTICE_EVERY_MS) return
        lastNoticeAt = now
        pendingNotice = text
    }

    fun close() = ocr.close()

    companion object {
        private const val TAG = "TranslationPipeline"
        private const val ONLINE_KEY_PREFIX = "g:"
        /** После ошибки онлайна какое-то время сразу переводим офлайн, потом пробуем снова. */
        private const val RETRY_ONLINE_MS = 30_000L
        private const val NOTICE_EVERY_MS = 60_000L

        /** Вырезать область (координаты кадра). Если область = весь кадр — вернуть его же. */
        fun crop(full: Bitmap, box: Box): Bitmap {
            val b = box.clampTo(full.width, full.height)
            if (b.isEmpty) return full
            if (b.left == 0 && b.top == 0 && b.width == full.width && b.height == full.height) return full
            return Bitmap.createBitmap(full, b.left, b.top, b.width, b.height)
        }

        /** Почти чёрный кадр — так выглядят приложения с запретом снимков на Android 11–13. */
        fun looksBlack(bmp: Bitmap): Boolean {
            val stepX = (bmp.width / 16).coerceAtLeast(1)
            val stepY = (bmp.height / 16).coerceAtLeast(1)
            var y = stepY / 2
            while (y < bmp.height) {
                var x = stepX / 2
                while (x < bmp.width) {
                    if (FrameDiff.luma(bmp.getPixel(x, y)) > 10) return false
                    x += stepX
                }
                y += stepY
            }
            return true
        }
    }
}
