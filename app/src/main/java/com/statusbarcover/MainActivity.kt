package com.statusbarcover

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var cfg: Cfg
    private lateinit var root: LinearLayout
    private val reloaders = mutableListOf<() -> Unit>()
    private var loading = false

    private lateinit var permText: TextView
    private lateinit var permBtn: Button
    private lateinit var accText: TextView
    private lateinit var accBtn: Button
    private lateinit var pickBtn: Button
    private lateinit var swEnabled: Switch
    private lateinit var swEdit: Switch

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { reload() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg(this)
        title = "Шторка OLED"

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        build()

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        cfg.sp.registerOnSharedPreferenceChangeListener(prefListener)
        reload()
        syncService()
    }

    override fun onPause() {
        cfg.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onPause()
    }

    override fun onStop() {
        // редактор не оставляем включённым, когда ушли из приложения
        if (cfg.edit) {
            cfg.edit = false
            syncService()
        }
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, AppWatcherService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, true) }
    }

    /** Служба работает, пока включена полоса или открыт редактор. */
    private fun syncService() {
        if ((cfg.enabled || cfg.edit) && canDraw()) OverlayService.start(this) else OverlayService.stop(this)
    }

    // ---------- построение интерфейса ----------

    private fun header(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(24), 0, dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, dp(2), 0, dp(6))
        root.addView(this)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun switch(text: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch = Switch(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> if (!loading) set(checked) }
        reloaders += { isChecked = get() }
        root.addView(this)
    }

    private fun slider(label: String, unit: String, min: Int, max: () -> Int, get: () -> Int, set: (Int) -> Unit) {
        val tv = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, 0) }
        val sb = SeekBar(this)
        fun show(v: Int) { tv.text = "$label: $v $unit" }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                show(min + p)
                if (fromUser && !loading) set(min + p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        reloaders += {
            val m = max().coerceAtLeast(min + 1)
            sb.max = m - min
            val v = get().coerceIn(min, m)
            sb.progress = v - min
            show(v)
        }
        root.addView(tv)
        root.addView(sb)
    }

    private fun build() {
        header("Полоса")
        permText = note("")
        permBtn = button("Выдать разрешение «Поверх других окон»") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        swEnabled = switch("Включить полосу", { cfg.enabled }) { on ->
            if (on && !canDraw()) {
                loading = true; swEnabled.isChecked = false; loading = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                cfg.enabled = on
                syncService()
            }
        }
        switch("Автозапуск при включении телефона", { cfg.autostart }) { cfg.autostart = it }
        slider("Непрозрачность", "%", 0, { 100 }, { cfg.alphaPct }) { cfg.alphaPct = it }
        note("100% — полностью чёрная, скрывает часы и значки. Поменьше — значки видны, но тусклее.")
        slider("Исчезает по нажатию на", "сек", 1, { 30 }, { cfg.hideSec }) { cfg.hideSec = it }
        note("Нажмите на полосу — она пропадёт на это время, и можно открыть шторку.")

        header("Где показывать")
        val group = RadioGroup(this)
        val all = RadioButton(this).apply { text = "Во всех приложениях"; id = 1 }
        val some = RadioButton(this).apply { text = "Только в выбранных"; id = 2 }
        group.addView(all)
        group.addView(some)
        group.setOnCheckedChangeListener { _, id -> if (!loading) { cfg.onlySelected = id == 2; reload() } }
        reloaders += { group.check(if (cfg.onlySelected) 2 else 1) }
        root.addView(group)
        pickBtn = button("Выбрать приложения") { startActivity(Intent(this, AppPickerActivity::class.java)) }
        accText = note("")
        accBtn = button("Включить службу слежения за приложением") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        header("Редактор положения")
        swEdit = switch("Режим перетаскивания", { cfg.edit }) { on ->
            if (on && !canDraw()) {
                loading = true; swEdit.isChecked = false; loading = false
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                cfg.edit = on
                syncService()
            }
        }
        note("Включите и тяните красную рамку пальцем, либо подгоните ползунками. Режим выключается сам, когда вы выходите из приложения.")
        slider("Слева (X)", "px", 0, { cfg.screenW - 20 }, { cfg.x }) { cfg.x = it }
        slider("Сверху (Y)", "px", 0, { cfg.screenH / 3 }, { cfg.y }) { cfg.y = it }
        slider("Ширина", "px", 20, { cfg.screenW }, { cfg.effW }) { cfg.w = it }
        slider("Высота", "px", 10, { 400 }, { cfg.effH }) { cfg.h = it }
        button("Сбросить положение") { cfg.reset(); reload() }
    }

    private fun reload() {
        loading = true
        reloaders.forEach { it() }
        val draw = canDraw()
        permText.text = if (draw) "Разрешение «Поверх других окон» выдано." else "Нужно разрешение «Поверх других окон», иначе полоса не появится."
        permBtn.visibility = if (draw) View.GONE else View.VISIBLE

        val sel = cfg.onlySelected
        val acc = accessibilityOn()
        pickBtn.visibility = if (sel) View.VISIBLE else View.GONE
        pickBtn.text = "Выбрать приложения (${cfg.apps.size})"
        accText.visibility = if (sel) View.VISIBLE else View.GONE
        accText.text = if (acc) "Служба слежения включена." else
            "Чтобы полоса знала, какое приложение открыто, включите службу «Шторка OLED» в «Специальных возможностях». Она только узнаёт название приложения."
        accBtn.visibility = if (sel && !acc) View.VISIBLE else View.GONE
        loading = false
    }
}
