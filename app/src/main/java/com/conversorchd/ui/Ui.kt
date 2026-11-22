package com.conversorchd.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Tokens visuais (Material Design 3, esquema escuro adaptado para OLED) e
 * fábricas de componentes. A interface é construída em código, sem bibliotecas
 * externas, para manter o APK pequeno e o desenho leve.
 */
object Ui {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF0B0C0E.toInt()
    const val SURFACE_HIGH = 0xFF17191D.toInt()
    const val OUTLINE = 0xFF2E3137.toInt()
    const val OUTLINE_SOFT = 0xFF1B1D21.toInt()
    const val ON_SURFACE = 0xFFE3E2E6.toInt()
    const val ON_SURFACE_VAR = 0xFFA8ABB3.toInt()
    const val PRIMARY = 0xFF9ECAFF.toInt()
    const val ON_PRIMARY = 0xFF00325A.toInt()
    const val TONAL = 0xFF17222F.toInt()
    const val ON_TONAL = 0xFFD2E4FF.toInt()
    const val ERROR = 0xFFFFB4AB.toInt()
    const val WARN = 0xFFFFCF87.toInt()
    const val SUCCESS = 0xFF9DD8A6.toInt()
    const val FOCUS = 0xFFFFFFFF.toInt()

    enum class Style { FILLED, TONAL, OUTLINED, TEXT }

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun text(ctx: Context, s: CharSequence = "", sp: Float = 14f, color: Int = ON_SURFACE, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(0f, 1.1f)
        }

    fun singleLine(tv: TextView, middle: Boolean = true): TextView = tv.apply {
        maxLines = 1
        ellipsize = if (middle) TextUtils.TruncateAt.MIDDLE else TextUtils.TruncateAt.END
    }

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /** Fundo com estados: foco (anel branco, essencial para D-pad), desativado e normal, com ripple. */
    fun stateBackground(ctx: Context, fill: Int, stroke: Int, radiusDp: Int, ripple: Int): Drawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, fill, radiusDp, FOCUS, 3))
        states.addState(intArrayOf(-android.R.attr.state_enabled), rounded(ctx, withAlpha(fill, 0.35f), radiusDp,
            if (stroke != 0) withAlpha(stroke, 0.35f) else 0, if (stroke != 0) 1 else 0))
        states.addState(IntArray(0), rounded(ctx, fill, radiusDp, stroke, if (stroke != 0) 1 else 0))
        val mask = rounded(ctx, Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(ripple), states, mask)
    }

    fun withAlpha(color: Int, a: Float): Int = Color.argb((Color.alpha(color) * a).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun button(ctx: Context, label: CharSequence, style: Style = Style.TONAL, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            stateListAnimator = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(ctx, 48)
            minimumHeight = dp(ctx, 48)
            minWidth = dp(ctx, 48)
            minimumWidth = dp(ctx, 48)
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
            isFocusable = true
            val (fill, stroke, fg) = when (style) {
                Style.FILLED -> Triple(PRIMARY, 0, ON_PRIMARY)
                Style.TONAL -> Triple(TONAL, 0, ON_TONAL)
                Style.OUTLINED -> Triple(BG, OUTLINE, PRIMARY)
                Style.TEXT -> Triple(Color.TRANSPARENT, 0, PRIMARY)
            }
            background = stateBackground(ctx, fill, stroke, 12, withAlpha(fg, 0.25f))
            setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), IntArray(0)),
                    intArrayOf(withAlpha(fg, 0.38f), fg),
                ),
            )
            setOnClickListener { onClick(it) }
        }

    /** Botão compacto (36 dp) para ações dentro dos cartões. */
    fun smallButton(ctx: Context, label: CharSequence, style: Style = Style.OUTLINED, onClick: (View) -> Unit): Button =
        button(ctx, label, style, onClick).apply {
            minHeight = dp(ctx, 36)
            minimumHeight = dp(ctx, 36)
            minWidth = dp(ctx, 36)
            minimumWidth = dp(ctx, 36)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        }

    /** Grupo segmentado (perfil, modo): o item selecionado fica preenchido. */
    fun segmented(ctx: Context, labels: List<String>, selected: Int, tagPrefix: String, onSelect: (Int) -> Unit): LinearLayout {
        val row = row(ctx, 6)
        labels.forEachIndexed { i, label ->
            val b = smallButton(ctx, label, if (i == selected) Style.FILLED else Style.OUTLINED) { onSelect(i) }
            b.tag = "$tagPrefix$i"
            b.minHeight = dp(ctx, 40)
            b.minimumHeight = dp(ctx, 40)
            row.addView(b, weighted())
        }
        return row
    }

    /** Rótulo pequeno em caixa alta para seções. */
    fun label(ctx: Context, s: String): TextView = text(ctx, s.uppercase(), 11f, ON_SURFACE_VAR, bold = true).apply {
        letterSpacing = 0.08f
    }

    /** Selo de estado (fundo suave na cor do estado). */
    fun badge(ctx: Context): TextView = text(ctx, "", 11f, PRIMARY, bold = true).apply {
        setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
    }

    fun setBadge(tv: TextView, s: String, color: Int) {
        tv.text = s
        tv.setTextColor(color)
        tv.background = rounded(tv.context, withAlpha(color, 0.14f), 6)
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, SURFACE, 14, OUTLINE_SOFT, 1)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
    }

    fun row(ctx: Context, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(ctx, gapDp), dp(ctx, gapDp)); setColor(Color.TRANSPARENT) }
    }

    fun column(ctx: Context, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(ctx, gapDp), dp(ctx, gapDp)); setColor(Color.TRANSPARENT) }
    }

    fun progressBar(ctx: Context): ProgressBar =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            val track = rounded(ctx, SURFACE_HIGH, 4)
            val bar = ClipDrawable(rounded(ctx, PRIMARY, 4), Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(track, bar)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
            minimumHeight = dp(ctx, 6)
        }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
}
