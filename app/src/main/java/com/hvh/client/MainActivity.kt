package com.hvh.client

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

/** Лаунчер: список модулей с переключателями и настройками + запуск Minecraft PE. */
class MainActivity : AppCompatActivity() {
    private val mcpe = "com.mojang.minecraftpe"
    private lateinit var servers: ServerStore
    private lateinit var serverBox: LinearLayout
    private lateinit var proxyStatus: TextView

    // Палитра (та же, что в макете)
    private val bg = Color.parseColor("#12151C")
    private val panel = Color.parseColor("#1B202B")
    private val panel2 = Color.parseColor("#232A38")
    private val line = Color.parseColor("#2F384A")
    private val fg = Color.parseColor("#E6EBF5")
    private val dim = Color.parseColor("#8A95AB")
    private val accent = Color.parseColor("#6DFF8A")
    private val accentInk = Color.parseColor("#07210D")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        typeface = if (bold) mono else Typeface.MONOSPACE
    }

    private fun square(fill: Int, stroke: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(fill); if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = panel
        window.navigationBarColor = bg
        val cfg = Config(this).also { it.export() }
        servers = ServerStore(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // Шапка
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = square(panel); setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        header.addView(text("HVH CLIENT", 16f, fg, true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(text(gameVersion(), 13f, dim))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Кнопка запуска
        list.addView(Button(this).apply {
            text = "ЗАПУСТИТЬ MINECRAFT"; typeface = mono; setTextColor(accentInk)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); isAllCaps = false
            background = square(accent, Color.parseColor("#3FBF5C"), 3)
            setOnClickListener { launchGame() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { setMargins(dp(16), dp(16), dp(16), dp(12)) })

        list.addView(serverSection())
        for (m in Modules.all) list.addView(moduleView(cfg, m))

        list.addView(text("Настройки применяются при следующем запуске игры.", 12f, dim).apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
        })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ---- Серверы и прокси ----
    private fun serverSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        body.addView(text("СЕРВЕРЫ", 14f, fg, true))
        body.addView(text("Нажми, чтобы выбрать. Долгое нажатие: изменить или удалить.", 12f, dim))
        serverBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(serverBox)
        body.addView(Button(this).apply {
            text = "+ ДОБАВИТЬ СЕРВЕР"; typeface = mono; isAllCaps = false; setTextColor(accent)
            background = square(panel2, accent, 2)
            setOnClickListener { editServer(-1) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        body.addView(Button(this).apply {
            text = "ЗАПУСТИТЬ ПРОКСИ"; typeface = mono; isAllCaps = false; setTextColor(accentInk)
            background = square(accent, Color.parseColor("#3FBF5C"), 2)
            setOnClickListener { toggleProxy(this) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        proxyStatus = text("В игре добавь сервер 127.0.0.1, порт ${ProxyController.LISTEN_PORT}", 12f, dim).apply { setPadding(0, dp(8), 0, 0) }
        body.addView(proxyStatus)
        box.addView(body)
        renderServers()
        return box
    }

    private fun renderServers() {
        serverBox.removeAllViews()
        val list = servers.all()
        if (list.isEmpty()) {
            serverBox.addView(text("Серверов нет. Добавь первый.", 13f, dim).apply { setPadding(0, dp(10), 0, dp(4)) })
            return
        }
        list.forEachIndexed { i, s ->
            val sel = i == servers.selected
            serverBox.addView(text((if (sel) "▶ " else "   ") + s.name + "  " + s.address, 14f, if (sel) accentInk else fg, sel).apply {
                background = square(if (sel) accent else panel, line, 1)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { servers.selected = i; renderServers() }
                setOnLongClickListener { serverMenu(i); true }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    private fun serverMenu(i: Int) {
        AlertDialog.Builder(this).setItems(arrayOf("Изменить", "Удалить")) { _, which ->
            if (which == 0) editServer(i) else { servers.remove(i); renderServers() }
        }.show()
    }

    private fun editServer(index: Int) {
        val old = servers.all().getOrNull(index)
        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint; setText(value); inputType = type; setSingleLine()
        }
        val name = field("Название", old?.name ?: "", InputType.TYPE_CLASS_TEXT)
        val host = field("IP или адрес", old?.host ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val port = field("Порт", (old?.port ?: 19132).toString(), InputType.TYPE_CLASS_NUMBER)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(host); addView(port)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (old == null) "Новый сервер" else "Изменить сервер")
            .setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = ServerStore.validate(name.text.toString(), host.text.toString(), port.text.toString())
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                val s = Server(name.text.toString().trim(), host.text.toString().trim(), port.text.toString().toInt())
                if (old == null) servers.add(s) else servers.update(index, s)
                renderServers(); dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun toggleProxy(btn: Button) {
        if (ProxyController.running) { ProxyController.stop(); btn.text = "ЗАПУСТИТЬ ПРОКСИ"; return }
        val s = servers.current()
        if (s == null) { Toast.makeText(this, "Сначала добавь сервер", Toast.LENGTH_SHORT).show(); return }
        when (val r = ProxyController.start(s)) {
            is ProxyController.Result.Started -> { btn.text = "ОСТАНОВИТЬ ПРОКСИ"; proxyStatus.text = r.info }
            is ProxyController.Result.Failed -> proxyStatus.text = r.reason
        }
    }

    private fun moduleView(cfg: Config, m: ModuleDef): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(m.title.uppercase(), 14f, fg, true))
        names.addView(text(m.description, 12f, dim))
        top.addView(names, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(6), 0, 0) }
        val sw = SwitchCompat(this).apply {
            isChecked = cfg.enabled(m.id)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, dim))
            trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#2C7A40"), panel2))
            settingsBox.alpha = if (isChecked) 1f else 0.45f
            setOnCheckedChangeListener { _, v -> cfg.setEnabled(m.id, v); settingsBox.alpha = if (v) 1f else 0.45f }
        }
        top.addView(sw)
        body.addView(top)

        for (s in m.settings) {
            val label = text("", 12f, dim)
            val value = text("", 12f, accent, true)
            fun show(v: Float) { label.text = s.title; value.text = format(v) }
            show(cfg.value(m.id, s))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); row.addView(value)
            val bar = SeekBar(this).apply {
                max = 100
                progress = (((cfg.value(m.id, s) - s.min) / (s.max - s.min)) * 100).roundToInt()
                progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
                progressBackgroundTintList = ColorStateList.valueOf(line)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = snap(s, s.min + (s.max - s.min) * p / 100f)
                        cfg.setValue(m.id, s, v); show(v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            settingsBox.addView(row); settingsBox.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        body.addView(settingsBox)
        card.addView(body)
        return card
    }

    /** Широкие диапазоны округляем до целых, узкие — до 0.5. */
    private fun snap(s: Setting, v: Float): Float =
        if (s.max - s.min >= 10f) v.roundToInt().toFloat() else (v * 2).roundToInt() / 2f

    private fun format(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

    private fun gameVersion(): String = try {
        "MCPE " + packageManager.getPackageInfo(mcpe, 0).versionName
    } catch (e: Exception) { "MCPE не найден" }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(mcpe)
        if (i == null) Toast.makeText(this, "Minecraft PE не установлен", Toast.LENGTH_LONG).show()
        else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
