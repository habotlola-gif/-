package com.statusbarcover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager

/**
 * Запасной вариант: рисует полосу обычным окном «поверх других».
 * Когда включена служба специальных возможностей, полосу рисует она (выше строки состояния), а это окно убирается.
 */
class OverlayService : Service() {

    private lateinit var cfg: Cfg
    private lateinit var controller: OverlayController
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = Cfg(this)
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // редактор не должен «залипать» после перезапуска
        cfg.edit = false
        controller = OverlayController(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        syncHost()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::controller.isInitialized) controller.refresh()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::controller.isInitialized) controller.detach()
        super.onDestroy()
    }

    /** Если полосу уже рисует служба специальных возможностей, свою убираем. */
    fun syncHost() {
        if (!::controller.isInitialized) return
        if (AppWatcherService.instance != null) controller.detach() else controller.attach()
    }

    fun refresh() {
        if (::controller.isInitialized) controller.refresh()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Полоса работает", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Полоса поверх строки состояния активна")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    companion object {
        private const val CHANNEL = "overlay"

        @Volatile
        var instance: OverlayService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
