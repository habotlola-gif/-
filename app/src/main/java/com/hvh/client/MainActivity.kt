package com.hvh.client

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private val mcpe = "com.mojang.minecraftpe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Config(this).also { it.export() }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        root.addView(Button(this).apply {
            text = "Запустить Minecraft"
            setOnClickListener { launchGame() }
        })

        for (m in Modules.all) {
            root.addView(Switch(this).apply {
                text = "${m.title} — ${m.description}"
                isChecked = cfg.enabled(m.id)
                setOnCheckedChangeListener { _, v -> cfg.setEnabled(m.id, v) }
            })
            for (s in m.settings) {
                val label = TextView(this).apply { text = "   ${s.title}: ${cfg.value(m.id, s)}" }
                val bar = SeekBar(this).apply {
                    max = 100
                    progress = (((cfg.value(m.id, s) - s.min) / (s.max - s.min)) * 100).toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            if (!fromUser) return
                            val v = s.min + (s.max - s.min) * p / 100f
                            cfg.setValue(m.id, s, v)
                            label.text = "   ${s.title}: ${"%.1f".format(v)}"
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                }
                root.addView(label); root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(mcpe)
        if (i == null) Toast.makeText(this, "Minecraft PE не установлен", Toast.LENGTH_LONG).show()
        else startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
