package com.example.shadetiles

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
        val ok = hasSecurePermission()
        tv.text = (if (ok) "✅ Разрешение выдано\n\n" else "❌ Разрешение не выдано\n\n") +
            "1. Подключите телефон к ПК (отладка по USB включена) и выполните один раз:\n\n" +
            "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n\n" +
            "2. Потяните шторку вниз → значок карандаша (изменить) → перетащите плитки " +
            "«Инверсия», «Ч/Б экран», «Высокий контраст» в быстрые настройки.\n\n" +
            "3. Нажимайте плитку — режим включается/выключается."
    }

}
