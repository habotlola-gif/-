package com.hvh.client

import android.content.Context
import java.io.File

/** Хранит состояние модулей и пишет его в простой key=value файл, который читает нативная библиотека. */
class Config(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hvh", Context.MODE_PRIVATE)

    fun enabled(id: String) = prefs.getBoolean("$id.enabled", false)
    fun setEnabled(id: String, v: Boolean) { prefs.edit().putBoolean("$id.enabled", v).apply(); export() }

    fun value(id: String, s: Setting) = prefs.getFloat("$id.${s.key}", s.default)
    fun setValue(id: String, s: Setting, v: Float) { prefs.edit().putFloat("$id.${s.key}", v).apply(); export() }

    /** Файл конфигурации; путь можно передать в нативный код при инъекции. */
    fun file(): File = File(ctx.getExternalFilesDir(null), "hvh.cfg")

    fun export() {
        val sb = StringBuilder()
        for (m in Modules.all) {
            sb.append("${m.id}.enabled=${if (enabled(m.id)) 1 else 0}\n")
            for (s in m.settings) sb.append("${m.id}.${s.key}=${value(m.id, s)}\n")
        }
        file().writeText(sb.toString())
    }
}
