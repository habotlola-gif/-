package com.statusbarcover

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Служба специальных возможностей: рисует полосу выше строки состояния
 * (окно TYPE_ACCESSIBILITY_OVERLAY) и узнаёт, какое приложение сейчас открыто.
 * Содержимое экрана не читает.
 */
class AppWatcherService : AccessibilityService() {

    private var controller: OverlayController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val cfg = Cfg(this)
        // «Автозапуск» выключен: сразу после загрузки телефона полосу не включаем
        if (!cfg.autostart && SystemClock.elapsedRealtime() < 3 * 60 * 1000L) cfg.enabled = false
        controller = OverlayController(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also { it.attach() }
        OverlayService.instance?.syncHost()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || isKeyboard(pkg)) return
        OverlayController.fgPackage = pkg
        controller?.refresh()
        OverlayService.instance?.refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.refresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        controller?.detach()
        controller = null
        if (instance === this) instance = null
        OverlayService.instance?.syncHost()
    }

    private fun isKeyboard(pkg: String): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}

    companion object {
        @Volatile
        var instance: AppWatcherService? = null
    }
}
