package com.personal.screentranslator

import android.graphics.Bitmap

/** OCR → склейка → фильтр → (кеш | перевод) → словарь. */
class TranslationPipeline(private val app: App) {

    private val ocr = OcrEngine()

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
        val key = lang.code + "|" + prepared.text
        val raw = app.cache.get(key) ?: app.translator.translate(prepared.text, lang).also {
            if (app.cache.put(key, it)) app.saveCacheAsync()
        }
        return app.dictionary.finish(raw, prepared)
    }

    fun close() = ocr.close()

    companion object {
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
