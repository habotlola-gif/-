package com.statusbarcover

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/** Узнаёт, какое приложение сейчас на экране. Ничего другого не читает. */
class AppWatcherService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || isKeyboard(pkg)) return
        OverlayService.onForegroundApp(pkg)
    }

    private fun isKeyboard(pkg: String): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}
}
