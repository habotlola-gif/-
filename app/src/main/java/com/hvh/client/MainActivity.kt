package com.hvh.client

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
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

        for (m in Modules.all) list.addView(moduleView(cfg, m))

        list.addView(text("Настройки применяются при следующем запуске игры.", 12f, dim).apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
        })
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
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
