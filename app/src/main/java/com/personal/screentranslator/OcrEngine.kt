package com.personal.screentranslator

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/** Распознавание текста ML Kit (модели внутри APK, работают офлайн). */
class OcrEngine {

    private val recognizers = HashMap<SourceLang, TextRecognizer>()

    private fun recognizer(lang: SourceLang): TextRecognizer = recognizers.getOrPut(lang) {
        when (lang) {
            SourceLang.EN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            SourceLang.JA -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            SourceLang.ZH -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            SourceLang.KO -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
    }

    /** Блоки текста в координатах переданного bitmap. */
    suspend fun recognize(bitmap: Bitmap, lang: SourceLang): List<OcrBlock> {
        val text = recognizer(lang).process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.mapNotNull { block ->
            val lines = block.lines.mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                val t = line.text.trim()
                if (t.isEmpty()) null else OcrLine(t, Box(r.left, r.top, r.right, r.bottom))
            }
            if (lines.isEmpty()) null else OcrBlock(lines)
        }
    }

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
