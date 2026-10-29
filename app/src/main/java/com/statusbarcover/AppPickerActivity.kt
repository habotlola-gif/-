package com.statusbarcover

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Список установленных приложений с галочками. */
class AppPickerActivity : Activity() {

    private class Item(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var cfg: Cfg
    private val selected = HashSet<String>()
    private var items: List<Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg(this)
        selected.addAll(cfg.apps)
        val list = ListView(this).apply {
            setBackgroundColor(Ui.BG)
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = dp(6)
            setPadding(dp(8), 0, dp(8), dp(16))
            clipToPadding = false
            addHeaderView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(24), dp(12), dp(12))
                addView(TextView(context).apply {
                    text = "Приложения"
                    textSize = 28f
                    setTextColor(Ui.TEXT)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = "Отметьте, где показывать полосу"
                    textSize = 14f
                    setTextColor(Ui.SUB)
                })
            }, null, false)
        }
        setContentView(list)

        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val loaded = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName to it }
                .distinctBy { it.first }
                .filter { it.first != packageName }
                .map { (pkg, ri) -> Item(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
                .sortedWith(compareByDescending<Item> { it.pkg in selected }.thenBy { it.label.lowercase() })
            runOnUiThread {
                items = loaded
                list.adapter = Adapter()
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val item = items[i]
            val row = (convert as? LinearLayout) ?: LinearLayout(this@AppPickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(Ui.TEXT)
                    setPadding(dp(16), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(CheckBox(context).apply { isClickable = false; isFocusable = false; Ui.style(this) })
            }
            row.background = Ui.rounded(if (item.pkg in selected) 0xFF2A1A10.toInt() else Ui.CARD, dp(18).toFloat())
            (row.getChildAt(0) as ImageView).setImageDrawable(item.icon)
            (row.getChildAt(1) as TextView).text = item.label
            val box = row.getChildAt(2) as CheckBox
            box.isChecked = item.pkg in selected
            row.setOnClickListener {
                if (!selected.remove(item.pkg)) selected.add(item.pkg)
                cfg.apps = selected
                box.isChecked = item.pkg in selected
                row.background = Ui.rounded(if (item.pkg in selected) 0xFF2A1A10.toInt() else Ui.CARD, dp(18).toFloat())
            }
            return row
        }
    }
}
