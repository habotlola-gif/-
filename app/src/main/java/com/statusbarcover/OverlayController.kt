package com.statusbarcover

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Полоса поверх строки состояния. Тип окна задаёт владелец:
 * TYPE_ACCESSIBILITY_OVERLAY лежит выше строки состояния, TYPE_APPLICATION_OVERLAY на многих прошивках ниже неё.
 */
class OverlayController(private val ctx: Context, private val windowType: Int) {

    private val cfg = Cfg(ctx)
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var tempHidden = false
    private val showAgain = Runnable { tempHidden = false; refresh() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    val attached: Boolean get() = view != null

    fun attach() {
        if (view != null) return
        val v = View(ctx).apply {
            setOnTouchListener { _, ev -> onTouch(ev) }
            visibility = View.GONE
        }
        runCatching { wm.addView(v, params()) }.onFailure { return }
        view = v
        cfg.sp.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        tempHidden = false
        cfg.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun params(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            cfg.effW,
            cfg.effH,
            windowType,
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

    companion object {
        /** Какое приложение сейчас на экране (сообщает AppWatcherService). */
        @Volatile
        var fgPackage: String? = null
    }
}
