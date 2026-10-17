package com.personal.screentranslator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Где показывать перевод в авто-режиме относительно выбранной области. */
enum class PanelPosition(val title: String) {
    ABOVE("Над областью"),
    BELOW("Под областью"),
    TOP("Вверху экрана"),
}

enum class SourceLang(val code: String, val title: String, val cjk: Boolean) {
    EN("en", "Английский", false),
    JA("ja", "Японский", true),
    ZH("zh", "Китайский", true),
    KO("ko", "Корейский", false);

    companion object {
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: EN
    }
}

data class Settings(
    val sourceLang: SourceLang = SourceLang.EN,
    val autoIntervalMs: Int = 1000,
    val panelPosition: PanelPosition = PanelPosition.ABOVE,
    val panelTextSp: Int = 16,
    val dismissOnTouch: Boolean = true,
    val debugBoxes: Boolean = false,
    val buttonX: Int = -1,
    val buttonY: Int = -1,
    val regionLandscape: NormRect? = null,
    val regionPortrait: NormRect? = null,
) {
    fun region(landscape: Boolean) = if (landscape) regionLandscape else regionPortrait
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Prefs(context: Context) {

    private val store = context.applicationContext.dataStore

    private object K {
        val SOURCE = stringPreferencesKey("source_lang")
        val INTERVAL = intPreferencesKey("auto_interval_ms")
        val PANEL_POS = stringPreferencesKey("panel_position")
        val PANEL_SP = intPreferencesKey("panel_text_sp")
        val DISMISS = booleanPreferencesKey("dismiss_on_touch")
        val DEBUG = booleanPreferencesKey("debug_boxes")
        val BUTTON_X = intPreferencesKey("button_x")
        val BUTTON_Y = intPreferencesKey("button_y")
        val REGION_LAND = stringPreferencesKey("region_landscape")
        val REGION_PORT = stringPreferencesKey("region_portrait")
    }

    val flow: Flow<Settings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            Settings(
                sourceLang = SourceLang.of(p[K.SOURCE]),
                autoIntervalMs = p[K.INTERVAL] ?: 1000,
                panelPosition = PanelPosition.entries.firstOrNull { it.name == p[K.PANEL_POS] } ?: PanelPosition.ABOVE,
                panelTextSp = p[K.PANEL_SP] ?: 16,
                dismissOnTouch = p[K.DISMISS] ?: true,
                debugBoxes = p[K.DEBUG] ?: false,
                buttonX = p[K.BUTTON_X] ?: -1,
                buttonY = p[K.BUTTON_Y] ?: -1,
                regionLandscape = NormRect.decode(p[K.REGION_LAND]),
                regionPortrait = NormRect.decode(p[K.REGION_PORT]),
            )
        }

    suspend fun setSourceLang(v: SourceLang) = store.edit { it[K.SOURCE] = v.code }
    suspend fun setAutoInterval(ms: Int) = store.edit { it[K.INTERVAL] = ms }
    suspend fun setPanelPosition(v: PanelPosition) = store.edit { it[K.PANEL_POS] = v.name }
    suspend fun setPanelTextSp(v: Int) = store.edit { it[K.PANEL_SP] = v }
    suspend fun setDismissOnTouch(v: Boolean) = store.edit { it[K.DISMISS] = v }
    suspend fun setDebugBoxes(v: Boolean) = store.edit { it[K.DEBUG] = v }

    suspend fun setButtonPosition(x: Int, y: Int) = store.edit {
        it[K.BUTTON_X] = x
        it[K.BUTTON_Y] = y
    }

    suspend fun setRegion(landscape: Boolean, rect: NormRect?) = store.edit {
        val key = if (landscape) K.REGION_LAND else K.REGION_PORT
        if (rect == null) it.remove(key) else it[key] = rect.encode()
    }
}
