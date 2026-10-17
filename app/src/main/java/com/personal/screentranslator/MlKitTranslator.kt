package com.personal.screentranslator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import com.google.mlkit.nl.translate.Translator as MlTranslator

/** Переводчик. Интерфейс — чтобы позже можно было подключить онлайн-ИИ без переделки. */
interface Translator {
    suspend fun translate(text: String, source: SourceLang): String

    /** Сбросить проверки и клиентов (после скачивания или удаления пакетов). */
    fun reset()
}

class MissingModelException(val lang: String) :
    Exception("Не скачан языковой пакет: ${LanguagePacks.title(lang)}")

class MlKitTranslator : Translator {

    private val clients = HashMap<SourceLang, MlTranslator>()
    private val verified = HashSet<String>()

    override suspend fun translate(text: String, source: SourceLang): String {
        for (lang in listOf(source.code, LanguagePacks.TARGET)) {
            if (lang in verified) continue
            if (!LanguagePacks.isDownloaded(lang)) throw MissingModelException(lang)
            verified += lang
        }
        val client = clients.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(LanguagePacks.mlKitCode(source.code))
                    .setTargetLanguage(TranslateLanguage.RUSSIAN)
                    .build()
            )
        }
        return client.translate(text).await()
    }

    override fun reset() {
        verified.clear()
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
    }
}

/** Языковые пакеты перевода ML Kit (~30 МБ каждый, качаются один раз). */
object LanguagePacks {
    const val TARGET = "ru"
    val ALL = listOf("en", "ja", "zh", "ko", "ru")

    private val manager get() = RemoteModelManager.getInstance()

    fun title(code: String) = when (code) {
        "en" -> "Английский"
        "ja" -> "Японский"
        "zh" -> "Китайский"
        "ko" -> "Корейский"
        "ru" -> "Русский"
        else -> code
    }

    fun mlKitCode(code: String): String = when (code) {
        "en" -> TranslateLanguage.ENGLISH
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        "ko" -> TranslateLanguage.KOREAN
        "ru" -> TranslateLanguage.RUSSIAN
        else -> throw IllegalArgumentException(code)
    }

    private fun model(code: String) = TranslateRemoteModel.Builder(mlKitCode(code)).build()

    suspend fun downloaded(): Set<String> =
        manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()

    suspend fun isDownloaded(code: String): Boolean = manager.isModelDownloaded(model(code)).await()

    suspend fun download(code: String) {
        manager.download(model(code), DownloadConditions.Builder().build()).await()
    }

    suspend fun delete(code: String) {
        manager.deleteDownloadedModel(model(code)).await()
    }
}
