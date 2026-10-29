package com.statusbarcover

import android.content.Context
import android.content.SharedPreferences

/** Все настройки приложения в одном месте. */
class Cfg(private val ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = putBool("enabled", v)

    var autostart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = putBool("autostart", v)

    /** Режим редактора: полоса видна с красной рамкой и перетаскивается пальцем. */
    var edit: Boolean
        get() = sp.getBoolean("edit", false)
        set(v) = putBool("edit", v)

    /** false — полоса во всех приложениях, true — только в выбранных. */
    var onlySelected: Boolean
        get() = sp.getBoolean("onlySelected", false)
        set(v) = putBool("onlySelected", v)

    /** Непрозрачность полосы, 0..100. */
    var alphaPct: Int
        get() = sp.getInt("alphaPct", 100)
        set(v) = putInt("alphaPct", v)

    /** На сколько секунд полоса исчезает по нажатию. */
    var hideSec: Int
        get() = sp.getInt("hideSec", 5)
        set(v) = putInt("hideSec", v)

    var x: Int
        get() = sp.getInt("x", 0)
        set(v) = putInt("x", v)

    var y: Int
        get() = sp.getInt("y", 0)
        set(v) = putInt("y", v)

    /** -1 — на всю ширину экрана. */
    var w: Int
        get() = sp.getInt("w", -1)
        set(v) = putInt("w", v)

    /** -1 — высота строки состояния. */
    var h: Int
        get() = sp.getInt("h", -1)
        set(v) = putInt("h", v)

    var apps: Set<String>
        get() = HashSet(sp.getStringSet("apps", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("apps", HashSet(v)).apply()

    fun setXY(nx: Int, ny: Int) = sp.edit().putInt("x", nx).putInt("y", ny).apply()

    fun reset() = sp.edit().putInt("x", 0).putInt("y", 0).putInt("w", -1).putInt("h", -1).apply()

    val screenW: Int get() = ctx.resources.displayMetrics.widthPixels
    val screenH: Int get() = ctx.resources.displayMetrics.heightPixels

    val statusBarH: Int
        get() {
            val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) ctx.resources.getDimensionPixelSize(id) else (24 * ctx.resources.displayMetrics.density).toInt()
        }

    val effW: Int get() = (if (w < 0) screenW else w).coerceIn(20, screenW)
    val effH: Int get() = (if (h < 0) statusBarH else h).coerceAtLeast(10)
}
