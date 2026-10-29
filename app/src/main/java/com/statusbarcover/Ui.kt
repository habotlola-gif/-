package com.statusbarcover

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Чёрно-оранжевая тема: цвета и заготовки виджетов. */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF151515.toInt()
    const val CARD_BTN = 0xFF262626.toInt()
    const val ORANGE = 0xFFE8763A.toInt()
    const val TEXT = 0xFFF5F5F5.toInt()
    const val SUB = 0xFF9A9A9A.toInt()
    const val OFF = 0xFF555555.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun states(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(on, off)
    )

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, dp(ctx, 22).toFloat())
        setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
    }

    fun sectionTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        isAllCaps = true
        letterSpacing = 0.08f
        setTextColor(ORANGE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(ctx, 8), dp(ctx, 20), 0, dp(ctx, 8))
    }

    fun label(ctx: Context, text: String, size: Float = 16f) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(TEXT)
    }

    fun note(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTextColor(SUB)
        setPadding(0, dp(ctx, 2), 0, dp(ctx, 8))
    }

    /** Скруглённая кнопка: основная — оранжевая, вторичная — тёмная с оранжевым текстом. */
    fun pill(ctx: Context, text: String, primary: Boolean = true, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        setTextColor(if (primary) BG else ORANGE)
        val bg = rounded(if (primary) ORANGE else CARD_BTN, dp(ctx, 26).toFloat())
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 50)).apply {
            topMargin = dp(ctx, 8)
        }
    }

    fun style(sw: Switch) {
        sw.setTextColor(TEXT)
        sw.thumbTintList = states(ORANGE, 0xFFBBBBBB.toInt())
        sw.trackTintList = states(0x66E8763A, OFF)
    }

    fun style(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(ORANGE)
        sb.progressBackgroundTintList = ColorStateList.valueOf(OFF)
        sb.thumbTintList = ColorStateList.valueOf(ORANGE)
    }

    fun style(rb: RadioButton) {
        rb.setTextColor(TEXT)
        rb.buttonTintList = states(ORANGE, SUB)
    }

    fun style(cb: CheckBox) {
        cb.buttonTintList = states(ORANGE, SUB)
    }
}
