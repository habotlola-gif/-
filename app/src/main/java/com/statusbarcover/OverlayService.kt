package com.statusbarcover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/** Рисует чёрную полосу поверх строки состояния. */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var cfg: Cfg
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tempHidden = false
    private val showAgain = Runnable { tempHidden = false; refresh() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = Cfg(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        // редактор не должен «залипать» после перезапуска
        cfg.edit = false
        view = View(this).apply {
            setOnTouchListener { _, ev -> onTouch(ev) }
            visibility = View.GONE
        }
        wm.addView(view, params())
        cfg.sp.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::cfg.isInitialized) cfg.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        super.onDestroy()
    }

    private fun params(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            cfg.effW,
            cfg.effH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = cfg.x
        lp.y = cfg.y
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        return lp
    }

    private fun appAllowed(): Boolean {
        if (!cfg.onlySelected) return true
        val fg = fgPackage ?: return false
        return fg in cfg.apps
    }

    fun refresh() {
        val v = view ?: return
        val show = cfg.edit || (cfg.enabled && !tempHidden && appAllowed())
        if (!show) {
            v.visibility = View.GONE
            return
        }
        v.background = GradientDrawable().apply {
            if (cfg.edit) {
                setColor(Color.argb(230, 0, 0, 0))
                setStroke(4, Color.RED)
            } else {
                setColor(Color.argb(cfg.alphaPct * 255 / 100, 0, 0, 0))
            }
        }
        runCatching { wm.updateViewLayout(v, params()) }
        v.visibility = View.VISIBLE
    }

    private fun onTouch(ev: MotionEvent): Boolean {
        if (cfg.edit) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = cfg.x
                    startY = cfg.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = (startX + (ev.rawX - downRawX)).toInt().coerceIn(0, cfg.screenW - 20)
                    val ny = (startY + (ev.rawY - downRawY)).toInt().coerceIn(0, cfg.screenH - 10)
                    cfg.setXY(nx, ny)
                }
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) hideTemporarily()
        return true
    }

    private fun hideTemporarily() {
        tempHidden = true
        refresh()
        handler.removeCallbacks(showAgain)
        handler.postDelayed(showAgain, cfg.hideSec * 1000L)
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
        var fgPackage: String? = null

        @Volatile
        var instance: OverlayService? = null

        fun onForegroundApp(pkg: String) {
            fgPackage = pkg
            instance?.let { s -> s.handler.post { s.refresh() } }
        }

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
