package com.personal.screentranslator

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SettingsScreen(resumeTick: Int) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val settings by app.settings.collectAsState()
    val running by TranslatorAccessibilityService.isRunning.collectAsState()
    val scope = rememberCoroutineScope()

    val serviceEnabled = remember(resumeTick) { SystemChecks.isServiceEnabled(context) }
    val batteryOk = remember(resumeTick) { SystemChecks.isIgnoringBattery(context) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ScreenTranslator", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Перевод экрана на русский прямо на телефоне, без интернета",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ServiceSection(serviceEnabled, running)
            BatterySection(batteryOk)
            LanguageSection(app, settings, resumeTick, scope)
            AutoSection(app, settings, scope)
            BehaviorSection(app, settings, scope)
            DictionarySection(app)
            CacheSection(app, resumeTick)
            HowToSection()

            Text(
                "Распознавание и перевод — Google ML Kit. Перевод выполняется на устройстве " +
                    "моделями Google Translate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ServiceSection(enabled: Boolean, running: Boolean) {
    val context = LocalContext.current
    Section("Сервис") {
        Text(
            when {
                running -> "✅ Работает — кнопка уже на экране"
                enabled -> "⚠️ Включён, но не запущен. Выключите и снова включите его в «Спец. возможностях»."
                else -> "❌ Выключен"
            }
        )
        Button(onClick = { SystemChecks.openAccessibilitySettings(context) }) {
            Text("Открыть «Спец. возможности»")
        }
        if (!running) {
            Hint(
                "Найдите ScreenTranslator в разделе «Скачанные» или «Установленные приложения» и включите.\n\n" +
                    "Если переключатель серый или пишет «Ограниченная настройка» (Android 13+):\n" +
                    "1. Сначала попробуйте включить — пусть система откажет.\n" +
                    "2. Нажмите кнопку ниже — откроется «О приложении».\n" +
                    "3. ⋮ в правом верхнем углу → «Разрешить ограниченные настройки».\n" +
                    "4. Вернитесь и включите сервис."
            )
            OutlinedButton(onClick = { SystemChecks.openAppDetails(context) }) {
                Text("Открыть «О приложении»")
            }
        }
    }
}

@Composable
private fun BatterySection(ok: Boolean) {
    val context = LocalContext.current
    Section("Батарея") {
        if (ok) {
            Text("✅ Оптимизация батареи отключена")
        } else {
            Hint("Иначе система может выгружать сервис, и кнопка пропадёт.")
            Button(onClick = { SystemChecks.requestIgnoreBattery(context) }) {
                Text("Отключить оптимизацию")
            }
        }
        Hint(
            "Xiaomi / Redmi / POCO: «О приложении» → «Контроль активности» → «Нет ограничений» " +
                "и включите «Автозапуск». Samsung: «Батарея» → «Без ограничений»."
        )
    }
}

@Composable
private fun LanguageSection(app: App, settings: Settings, resumeTick: Int, scope: CoroutineScope) {
    var downloaded by remember { mutableStateOf<Set<String>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        downloaded = runCatching { LanguagePacks.downloaded() }.getOrDefault(emptySet())
    }
    LaunchedEffect(resumeTick) { refresh() }

    val needed = buildSet {
        add(settings.sourceLang.code)
        add(LanguagePacks.TARGET)
        if (settings.sourceLang != SourceLang.EN) add("en") // перевод идёт через английский
    }

    Section("Языки") {
        Text("Язык оригинала", style = MaterialTheme.typography.labelLarge)
        SourceLang.entries.forEach { lang ->
            RadioRow(lang.title, selected = settings.sourceLang == lang) {
                scope.launch { app.prefs.setSourceLang(lang) }
            }
        }
        HorizontalDivider()
        Text("Языковые пакеты перевода", style = MaterialTheme.typography.labelLarge)
        Hint("~30 МБ каждый. Интернет нужен один раз — для скачивания.")

        LanguagePacks.ALL.forEach { code ->
            val isDl = downloaded?.contains(code) == true
            val isNeeded = code in needed
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LanguagePacks.title(code))
                    Text(
                        when {
                            downloaded == null -> "проверяю…"
                            isDl -> "скачан"
                            isNeeded -> "нужен — не скачан"
                            else -> "не скачан"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!isDl && isNeeded && downloaded != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    busy == code -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    isDl -> TextButton(enabled = busy == null, onClick = {
                        busy = code
                        error = null
                        scope.launch {
                            runCatching { LanguagePacks.delete(code) }
                                .onFailure { error = "Не удалось удалить: ${it.message}" }
                            app.translator.reset()
                            refresh()
                            busy = null
                        }
                    }) { Text("Удалить") }
                    else -> Button(enabled = busy == null && downloaded != null, onClick = {
                        busy = code
                        error = null
                        scope.launch {
                            runCatching { LanguagePacks.download(code) }
                                .onFailure { error = "Не удалось скачать ${LanguagePacks.title(code)} — проверьте интернет" }
                            app.translator.reset()
                            refresh()
                            busy = null
                        }
                    }) { Text("Скачать") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AutoSection(app: App, settings: Settings, scope: CoroutineScope) {
    Section("Авто-режим (для новелл)") {
        Hint(
            "Включается в меню кнопки (долгий тап). Перевод окна диалога появляется сам, " +
                "когда меняется реплика. Нужна выбранная область."
        )
        var interval by remember(settings.autoIntervalMs) { mutableFloatStateOf(settings.autoIntervalMs.toFloat()) }
        Text("Проверять экран раз в ${"%.2f".format(interval / 1000f).trimEnd('0').trimEnd(',', '.')} с")
        Slider(
            value = interval,
            onValueChange = { interval = it },
            valueRange = 500f..2000f,
            steps = 5,
            onValueChangeFinished = { scope.launch { app.prefs.setAutoInterval(interval.roundToInt()) } },
        )

        Text("Где показывать перевод", style = MaterialTheme.typography.labelLarge)
        PanelPosition.entries.forEach { pos ->
            RadioRow(pos.title, selected = settings.panelPosition == pos) {
                scope.launch { app.prefs.setPanelPosition(pos) }
            }
        }

        var textSp by remember(settings.panelTextSp) { mutableFloatStateOf(settings.panelTextSp.toFloat()) }
        Text("Размер текста: ${textSp.roundToInt()}")
        Slider(
            value = textSp,
            onValueChange = { textSp = it },
            valueRange = 12f..24f,
            steps = 11,
            onValueChangeFinished = { scope.launch { app.prefs.setPanelTextSp(textSp.roundToInt()) } },
        )
    }
}

@Composable
private fun BehaviorSection(app: App, settings: Settings, scope: CoroutineScope) {
    Section("Поведение") {
        SwitchRow("Убирать перевод при касании экрана", settings.dismissOnTouch) {
            scope.launch { app.prefs.setDismissOnTouch(it) }
        }
        SwitchRow("Режим проверки: только рамки распознанного текста", settings.debugBoxes) {
            scope.launch { app.prefs.setDebugBoxes(it) }
        }
        Hint("Режим проверки помогает убедиться, что текст находится точно, — перевод при этом не выполняется.")
    }
}

@Composable
private fun DictionarySection(app: App) {
    var text by rememberSaveable { mutableStateOf(app.dictionaryText()) }
    var saved by remember { mutableStateOf(false) }
    Section("Словарь") {
        Hint("Имена персонажей и термины. Формат описан в комментариях внутри поля.")
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            maxLines = 16,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                app.saveDictionary(text)
                saved = true
            }) { Text("Сохранить") }
            if (saved) Text("  Сохранено", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CacheSection(app: App, resumeTick: Int) {
    var count by remember(resumeTick) { mutableIntStateOf(app.cache.size) }
    Section("Кеш переводов") {
        Text("Сохранено переводов: $count")
        OutlinedButton(onClick = {
            app.cache.clear()
            count = 0
        }) { Text("Очистить") }
    }
}

@Composable
private fun HowToSection() {
    Section("Как пользоваться") {
        Hint(
            "• Тап по кнопке — перевести экран. Ещё тап или касание игры — убрать перевод.\n" +
                "• Долгий тап — меню: язык, область, авто-режим.\n" +
                "• Для новеллы: «Выбрать область» → обведите окно диалога → «Авто-режим: включить».\n" +
                "• Кнопку можно перетаскивать."
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
