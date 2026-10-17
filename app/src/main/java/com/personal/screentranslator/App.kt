package com.personal.screentranslator

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** Общие объекты для сервиса и экрана настроек (один процесс). */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var prefs: Prefs
        private set
    lateinit var settings: StateFlow<Settings>
        private set

    val dictionary = UserDictionary()
    lateinit var cache: TranslationCache
        private set
    val translator: Translator by lazy { MlKitTranslator() }

    private val dictionaryFile get() = File(filesDir, "dictionary.txt")

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Первое чтение синхронно: сервису нужны позиция кнопки и область сразу при старте.
        val initial = runBlocking { prefs.flow.first() }
        settings = prefs.flow.stateIn(appScope, SharingStarted.Eagerly, initial)

        dictionary.load(dictionaryText())
        cache = TranslationCache(File(filesDir, "translation_cache.tsv"))
        appScope.launch(Dispatchers.IO) { cache.load() }
    }

    fun dictionaryText(): String =
        dictionaryFile.takeIf { it.exists() }?.readText() ?: UserDictionary.DEFAULT_TEXT

    fun saveDictionary(text: String) {
        dictionaryFile.writeText(text)
        dictionary.load(text)
    }

    fun saveCacheAsync() {
        appScope.launch(Dispatchers.IO) { cache.save() }
    }
}
