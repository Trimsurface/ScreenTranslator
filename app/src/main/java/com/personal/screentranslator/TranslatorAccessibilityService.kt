package com.personal.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Сервис спец. возможностей: кнопка, оверлей и весь пайплайн перевода.
 * Содержимое окон не читает — только снимает экран.
 */
class TranslatorAccessibilityService : AccessibilityService() {

    private val app get() = application as App
    private val settings get() = app.settings.value
    private val scope = MainScope()

    private lateinit var wm: WindowManager
    private lateinit var capturer: ScreenCapturer
    private lateinit var pipeline: TranslationPipeline
    private var overlay: OverlayRenderer? = null
    private var button: FloatingButton? = null
    private var menu: FloatingMenu? = null
    private var selector: RegionSelector? = null
    private var auto: AutoWatcher? = null

    private val work = Mutex()
    private var foregroundPkg: String? = null
    private var autoPkg: String? = null
    private var ignoredPkgs: Set<String> = emptySet()
    private var lastToastAt = 0L

    // ---------- жизненный цикл ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        capturer = ScreenCapturer(this)
        pipeline = TranslationPipeline(app)
        ignoredPkgs = buildSet {
            add(packageName)
            add("com.android.systemui")
            SystemSettings.Secure.getString(contentResolver, SystemSettings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { add(it) }
        }

        overlay = OverlayRenderer(this, wm) { settings.dismissOnTouch }.also { it.attach() }
        menu = FloatingMenu(this, wm) {}
        selector = RegionSelector(this, wm)
        button = FloatingButton(this, wm, buttonCallbacks).also { it.attach(settings.buttonX, settings.buttonY) }
        running.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPkgs || pkg == foregroundPkg) return
        foregroundPkg = pkg
        // Ушли в другое приложение — старый перевод больше не к месту.
        if (overlay?.kind == OverlayView.Kind.BUBBLES || overlay?.kind == OverlayView.Kind.DEBUG) overlay?.clear()
        updateAutoIndicator()
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Поворот: координаты плашек устарели.
        overlay?.clear()
        menu?.dismiss()
        button?.keepOnScreen()
        auto?.requestNow()
    }

    override fun onDestroy() {
        running.value = false
        auto?.stop()
        scope.cancel()
        menu?.dismiss()
        selector?.dismiss()
        button?.detach()
        overlay?.detach()
        if (::pipeline.isInitialized) pipeline.close()
        app.saveCacheAsync()
        super.onDestroy()
    }

    // ---------- кнопка ----------

    private val buttonCallbacks = object : FloatingButton.Callbacks {
        override fun onTap() {
            menu?.dismiss()
            val a = auto
            if (a != null && a.isRunning) {
                a.requestNow()
                return
            }
            if (overlay?.hasContent == true) {
                overlay?.clear()
                return
            }
            if (work.isLocked) return
            scope.launch { work.withLock { translateOnce() } }
        }

        override fun onLongPress() = showMenu()

        override fun onMoved(x: Int, y: Int) {
            app.appScope.launch { app.prefs.setButtonPosition(x, y) }
        }
    }

    private fun showMenu() {
        val btn = button ?: return
        val s = settings
        val landscape = isLandscape()
        val hasRegion = s.region(landscape) != null
        val autoOn = auto?.isRunning == true
        val items = buildList {
            add(FloatingMenu.Item("Язык: ${s.sourceLang.title}") { cycleLanguage() })
            add(FloatingMenu.Item(if (s.onlineMode) "Перевод: онлайн (Google)" else "Перевод: офлайн") { toggleOnline() })
            add(FloatingMenu.Item(if (hasRegion) "Изменить область" else "Выбрать область") { selectRegion(startAutoAfter = false) })
            if (hasRegion) add(FloatingMenu.Item("Сбросить область (весь экран)") { setRegion(null) })
            add(FloatingMenu.Item(if (autoOn) "Авто-режим: выключить" else "Авто-режим: включить") {
                if (autoOn) stopAuto() else startAuto()
            })
            add(FloatingMenu.Item("Настройки") { openSettings() })
            add(FloatingMenu.Item("Выключить переводчик") { disableSelf() })
        }
        menu?.show(items, btn.box())
    }

    private fun cycleLanguage() {
        val all = SourceLang.entries
        val next = all[(all.indexOf(settings.sourceLang) + 1) % all.size]
        app.appScope.launch { app.prefs.setSourceLang(next) }
        toast("Язык оригинала: ${next.title}", force = true)
        auto?.requestNow()
    }

    private fun toggleOnline() {
        val online = !settings.onlineMode
        app.appScope.launch { app.prefs.setOnlineMode(online) }
        toast(if (online) "Онлайн-перевод (Google). Без интернета — офлайн" else "Офлайн-перевод на телефоне", force = true)
        auto?.requestNow()
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ---------- область ----------

    private fun isLandscape(): Boolean = realScreenSize().let { it.x > it.y }

    private fun currentRegionBox(screenW: Int, screenH: Int): Box? =
        settings.region(screenW > screenH)?.toBox(screenW, screenH)?.takeIf { !it.isEmpty }

    private fun selectRegion(startAutoAfter: Boolean) {
        overlay?.clear()
        val screen = realScreenSize()
        selector?.show(currentRegionBox(screen.x, screen.y)) { result ->
            when (result) {
                is RegionSelector.Result.Selected -> {
                    setRegion(NormRect.of(result.box, screen.x, screen.y), screen.x > screen.y)
                    if (startAutoAfter) startAuto(regionJustSet = true)
                }
                RegionSelector.Result.WholeScreen -> setRegion(null)
                RegionSelector.Result.Cancelled -> Unit
            }
        }
    }

    private fun setRegion(rect: NormRect?, landscape: Boolean = isLandscape()) {
        app.appScope.launch { app.prefs.setRegion(landscape, rect) }
        if (rect == null && auto?.isRunning == true) stopAuto()
        auto?.requestNow()
    }

    // ---------- ручной перевод ----------

    private suspend fun translateOnce() {
        val btn = button ?: return
        val ov = overlay ?: return
        val s = settings
        btn.setBusy(true)
        var full: Bitmap? = null
        try {
            val screen = realScreenSize()
            val regionGuess = currentRegionBox(screen.x, screen.y)
            full = captureHiding(regionGuess)
            val region = currentRegionBox(full.width, full.height)
            val crop = if (region != null) TranslationPipeline.crop(full, region) else full
            val segments = try {
                pipeline.recognize(crop, region?.left ?: 0, region?.top ?: 0, s.sourceLang)
            } finally {
                if (crop !== full) crop.recycle()
            }

            if (segments.isEmpty()) {
                toast(if (TranslationPipeline.looksBlack(full)) "Экран чёрный — похоже, приложение запрещает снимки" else "Текст не найден", force = true)
                return
            }
            if (s.debugBoxes) {
                ov.showDebug(segments, full.width, full.height)
                return
            }
            val items = coroutineScope {
                segments.map { seg -> async { seg to pipeline.translate(seg.text, s.sourceLang) } }.awaitAll()
            }
            ov.showBubbles(items, full.width, full.height)
            pipeline.takeNotice()?.let { toast(it, force = true) }
        } catch (e: CaptureException) {
            toast(e.userMessage, force = true)
        } catch (e: MissingModelException) {
            toast("${e.message}. Скачайте его в настройках.", force = true)
        } catch (e: NoInternetException) {
            pipeline.takeNotice()
            toast(e.message ?: "Нет интернета", force = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "translate failed", e)
            toast("Ошибка: ${e.message ?: e.javaClass.simpleName}", force = true)
        } finally {
            full?.recycle()
            btn.setBusy(false)
        }
    }

    /**
     * Перед снимком прячем свои окна, которые попадут в кадр, — иначе OCR прочитает наш же перевод.
     * area = null — снимаем весь экран, прячем всё.
     */
    private suspend fun captureHiding(area: Box?): Bitmap {
        val hidden = mutableListOf<View>()
        button?.let { if (area == null || it.box().intersects(area)) hidden += it.view }
        overlay?.let { if (it.hasContent && (area == null || it.intersects(area))) hidden += it.view }
        hidden.forEach { it.visibility = View.INVISIBLE }
        try {
            if (hidden.isNotEmpty()) delay(HIDE_DELAY_MS)
            return capturer.capture()
        } finally {
            hidden.forEach { it.visibility = View.VISIBLE }
        }
    }

    // ---------- авто-режим ----------

    private fun startAuto(regionJustSet: Boolean = false) {
        val screen = realScreenSize()
        if (!regionJustSet && currentRegionBox(screen.x, screen.y) == null) {
            toast("Сначала обведите окно диалога", force = true)
            selectRegion(startAutoAfter = true)
            return
        }
        overlay?.clear()
        autoPkg = foregroundPkg
        val watcher = auto ?: AutoWatcher(autoHost).also { auto = it }
        watcher.start(scope)
        updateAutoIndicator()
        toast("Авто-режим включён. Тап по кнопке — перевести сейчас", force = true)
    }

    private fun stopAuto() {
        auto?.stop()
        overlay?.clear()
        updateAutoIndicator()
    }

    private fun autoPausedByApp(): Boolean {
        val target = autoPkg ?: return false
        return foregroundPkg != null && foregroundPkg != target
    }

    private fun updateAutoIndicator() {
        val state = when {
            auto?.isRunning != true -> FloatingButton.AutoState.OFF
            autoPausedByApp() -> FloatingButton.AutoState.PAUSED
            else -> FloatingButton.AutoState.ACTIVE
        }
        button?.setAuto(state)
    }

    private val autoHost = object : AutoWatcher.Host {
        private val power by lazy { getSystemService(PowerManager::class.java) }

        override fun regionFor(screenW: Int, screenH: Int) = currentRegionBox(screenW, screenH)

        override fun intervalMs() = settings.autoIntervalMs.toLong()

        override fun isPaused() = work.isLocked || !power.isInteractive || autoPausedByApp() ||
            MainActivity.isVisible || selector?.isShowing == true || menu?.isShowing == true

        override suspend fun captureForRegion(): Bitmap {
            val screen = realScreenSize()
            return captureHiding(currentRegionBox(screen.x, screen.y) ?: Box(0, 0, screen.x, screen.y))
        }

        override suspend fun recognize(crop: Bitmap, dx: Int, dy: Int) =
            pipeline.recognize(crop, dx, dy, settings.sourceLang)

        override suspend fun translate(text: String): String = try {
            pipeline.translate(text, settings.sourceLang)
        } finally {
            pipeline.takeNotice()?.let { toast(it, force = true) }
        }

        override fun showPanel(text: String, region: Box, screenW: Int, screenH: Int) {
            val s = settings
            overlay?.showPanel(text, region, s.panelPosition, s.panelTextSp, screenW, screenH)
        }

        override fun hidePanel() {
            if (overlay?.kind == OverlayView.Kind.PANEL) overlay?.clear()
        }

        override fun onFatal(message: String) {
            toast(message, force = true)
            stopAuto()
        }
    }

    // ---------- мелочи ----------

    private fun toast(text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastToastAt < 3000) return
        lastToastAt = now
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "ScreenTranslator"
        /** Сколько ждать после скрытия своих окон, чтобы они точно пропали с кадра. */
        private const val HIDE_DELAY_MS = 90L

        private val running = MutableStateFlow(false)
        /** Сервис запущен прямо сейчас (для экрана настроек). */
        val isRunning: StateFlow<Boolean> get() = running
    }
}
