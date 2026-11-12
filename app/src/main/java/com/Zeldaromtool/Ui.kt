package com.zeldaromtool

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat

/** Ajudantes de visual, todos coloridos pela paleta do tema escolhido. */
class Ui(private val ctx: Context, val pal: Palette) {

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    fun ripple(d: Drawable) = RippleDrawable(ColorStateList.valueOf(0x33808080), d, null)

    fun setOn(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.35f
    }

    fun sectionTitle(t: String) = TextView(ctx).apply {
        text = t; textSize = 20f; setTextColor(pal.accent)
        setPadding(0, dp(24), 0, dp(8))
    }

    fun body(t: String = "") = TextView(ctx).apply {
        text = t; textSize = 14f; setTextColor(pal.fg)
        setPadding(0, dp(4), 0, dp(4))
    }

    /** Cartão arredondado com ícone redondo, nome e uma linha de status. */
    fun card(icon: String, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val badge = TextView(ctx).apply {
            text = icon; textSize = 22f; gravity = Gravity.CENTER
            background = shape(pal.badgeBg, 28)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        val name = TextView(ctx).apply {
            text = label; textSize = 16f; setTextColor(pal.fg)
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(ctx).apply { textSize = 13f; setTextColor(pal.muted) }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(name)
            addView(status)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ripple(shape(pal.cardBg, 24, pal.cardStroke, 1))
            addView(badge)
            addView(texts)
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        return row to status
    }

    fun roundButton(t: String, filled: Boolean, onClick: () -> Unit) = Button(ctx).apply {
        text = t
        setAllCaps(false)
        textSize = 15f
        stateListAnimator = null
        setTextColor(if (filled) pal.onAccent else pal.accent)
        background = ripple(if (filled) shape(pal.accent, 28) else shape(0, 28, pal.accent, 1))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(0, dp(6), 0, dp(6)) }
    }

    /** Botão de ícone (engrenagem, três barras). */
    fun iconButton(resId: Int, description: String, onClick: (View) -> Unit) = ImageView(ctx).apply {
        setImageResource(resId)
        setColorFilter(pal.fg)
        contentDescription = description
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = ripple(shape(0, 24))
        isClickable = true
        setOnClickListener { onClick(it) }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    /** Barra de status e de navegação na cor do tema. */
    fun applyBars(activity: Activity) {
        val w = activity.window
        w.statusBarColor = pal.bg
        w.navigationBarColor = pal.bg
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.isAppearanceLightStatusBars = pal.lightBars
        c.isAppearanceLightNavigationBars = pal.lightBars
    }
}
