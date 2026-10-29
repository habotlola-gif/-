package com.statusbarcover

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Автозапуск после включения телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Cfg(context)
        if (cfg.enabled && cfg.autostart && Settings.canDrawOverlays(context)) {
            OverlayService.start(context)
        }
    }
}
