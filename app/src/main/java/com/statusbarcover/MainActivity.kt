package com.statusbarcover

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
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
    private lateinit var cur: LinearLayout
    private val reloaders = mutableListOf<() -> Unit>()
    private var loading = false

    /** Включить полосу, как только будет выдано разрешение. */
    private var pendingEnable = false

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

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            addView(root)
        })
        build()

        // автозапуск при входе в приложение
        if (savedInstanceState == null && cfg.autoOnOpen && !cfg.enabled) pendingEnable = true

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingEnable && canDraw()) {
            cfg.enabled = true
            pendingEnable = false
        }
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

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun canDraw() = Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

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

    private fun section(title: String?) {
        if (title != null) root.addView(Ui.sectionTitle(this, title))
        cur = Ui.card(this)
        root.addView(cur, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun note(text: String): TextView = Ui.note(this, text).also { cur.addView(it) }

    private fun button(text: String, primary: Boolean = true, onClick: () -> Unit): Button =
        Ui.pill(this, text, primary, onClick).also { cur.addView(it) }

    private fun switch(text: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch = Switch(this).apply {
        this.text = text
        textSize = 16f
        Ui.style(this)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> if (!loading) set(checked) }
        reloaders += { isChecked = get() }
        cur.addView(this)
    }

    private fun slider(label: String, unit: String, min: Int, max: () -> Int, get: () -> Int, set: (Int) -> Unit) {
        val tv = Ui.label(this, "", 15f).apply { setPadding(0, dp(8), 0, 0) }
        val sb = SeekBar(this).also { Ui.style(it) }
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
        cur.addView(tv)
        cur.addView(sb)
    }

    private fun build() {
        // шапка
        root.addView(TextView(this).apply {
            text = "Шторка OLED"
            textSize = 30f
            setTextColor(Ui.TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Чёрная полоса против выгорания строки состояния"
            textSize = 14f
            setTextColor(Ui.SUB)
            setPadding(dp(8), dp(2), 0, dp(4))
        })

        section("Полоса")
        permText = note("")
        permBtn = button("Выдать разрешение «Поверх других окон»") { openOverlaySettings() }
        accText = note("")
        accBtn = button("Включить службу «Шторка OLED»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        swEnabled = switch("Включить полосу", { cfg.enabled }) { on ->
            if (on && !canDraw()) {
                loading = true; swEnabled.isChecked = false; loading = false
                pendingEnable = true
                openOverlaySettings()
            } else {
                cfg.enabled = on
                syncService()
            }
        }
        switch("Включать при открытии приложения", { cfg.autoOnOpen }) { cfg.autoOnOpen = it }
        switch("Автозапуск при включении телефона", { cfg.autostart }) { cfg.autostart = it }

        section("Вид")
        slider("Непрозрачность", "%", 0, { 100 }, { cfg.alphaPct }) { cfg.alphaPct = it }
        note("100% — полностью чёрная, скрывает часы и значки.")
        slider("Исчезает по нажатию на", "сек", 1, { 30 }, { cfg.hideSec }) { cfg.hideSec = it }
        note("Нажмите на полосу — она пропадёт, и можно открыть шторку.")

        section("Где показывать")
        val group = RadioGroup(this)
        val all = RadioButton(this).apply { text = "Во всех приложениях"; id = 1; textSize = 16f; Ui.style(this) }
        val some = RadioButton(this).apply { text = "Только в выбранных"; id = 2; textSize = 16f; Ui.style(this) }
        group.addView(all)
        group.addView(some)
        group.setOnCheckedChangeListener { _, id -> if (!loading) { cfg.onlySelected = id == 2; reload() } }
        reloaders += { group.check(if (cfg.onlySelected) 2 else 1) }
        cur.addView(group)
        pickBtn = button("Выбрать приложения") { startActivity(Intent(this, AppPickerActivity::class.java)) }

        section("Редактор положения")
        swEdit = switch("Режим перетаскивания", { cfg.edit }) { on ->
            if (on && !canDraw()) {
                loading = true; swEdit.isChecked = false; loading = false
                openOverlaySettings()
            } else {
                cfg.edit = on
                syncService()
            }
        }
        note("Тяните красную рамку пальцем или подгоните ползунками. Режим сам выключается, когда вы выходите из приложения.")
        slider("Слева (X)", "px", 0, { cfg.screenW - 20 }, { cfg.x }) { cfg.x = it }
        slider("Сверху (Y)", "px", 0, { cfg.screenH / 3 }, { cfg.y }) { cfg.y = it }
        slider("Ширина", "px", 20, { cfg.screenW }, { cfg.effW }) { cfg.w = it }
        slider("Высота", "px", 10, { 400 }, { cfg.effH }) { cfg.h = it }
        button("Сбросить положение", false) { cfg.reset(); reload() }
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
        accText.text = if (acc) "Служба «Шторка OLED» включена: полоса рисуется выше строки состояния." else
            "Включите службу «Шторка OLED» в «Специальных возможностях»: без неё строка состояния на многих телефонах остаётся поверх полосы. Служба только рисует полосу и узнаёт название открытого приложения, экран не читает."
        accBtn.visibility = if (acc) View.GONE else View.VISIBLE
        loading = false
    }
}
