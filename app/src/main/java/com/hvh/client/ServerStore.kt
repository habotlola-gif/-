package com.hvh.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Сервер для подключения через прокси. */
data class Server(val name: String, val host: String, val port: Int) {
    val address get() = "$host:$port"
}

/** Список серверов (название, IP, порт) и выбранный сервер; хранится в SharedPreferences. */
class ServerStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("servers", Context.MODE_PRIVATE)

    fun all(): MutableList<Server> {
        val out = mutableListOf<Server>()
        try {
            val arr = JSONArray(prefs.getString("list", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Server(o.getString("name"), o.getString("host"), o.getInt("port")))
            }
        } catch (_: Exception) { /* повреждённые данные = пустой список */ }
        return out
    }

    private fun save(list: List<Server>) {
        val arr = JSONArray()
        for (s in list) arr.put(JSONObject().put("name", s.name).put("host", s.host).put("port", s.port))
        prefs.edit().putString("list", arr.toString()).apply()
    }

    var selected: Int
        get() = prefs.getInt("selected", 0).coerceAtLeast(0)
        set(v) { prefs.edit().putInt("selected", v).apply() }

    fun current(): Server? = all().getOrNull(selected)

    fun add(s: Server) { val l = all(); l.add(s); save(l); selected = l.size - 1 }
    fun update(i: Int, s: Server) { val l = all(); if (i in l.indices) { l[i] = s; save(l) } }
    fun remove(i: Int) {
        val l = all(); if (i !in l.indices) return
        l.removeAt(i); save(l)
        if (selected >= l.size) selected = (l.size - 1).coerceAtLeast(0)
    }

    companion object {
        /** Возвращает текст ошибки или null, если данные корректны. */
        fun validate(name: String, host: String, port: String): String? {
            if (name.isBlank()) return "Введи название"
            if (host.isBlank() || host.any { it.isWhitespace() }) return "Введи адрес без пробелов"
            val p = port.toIntOrNull() ?: return "Порт должен быть числом"
            if (p !in 1..65535) return "Порт от 1 до 65535"
            return null
        }
    }
}
