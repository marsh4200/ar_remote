package com.arsmarthome.remote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Palette from the approved preview. */
object Palette {
    val ground = Color.parseColor("#0E1116")
    val surface = Color.parseColor("#171B22")
    val raised = Color.parseColor("#1B2029")
    val track = Color.parseColor("#2A313C")
    val text = Color.parseColor("#F3F4F6")
    val soft = Color.parseColor("#C9CED6")
    val muted = Color.parseColor("#9AA3AF")
    val accent = Color.parseColor("#F2A541")
    val danger = Color.parseColor("#F9A487")
    val dangerBg = Color.parseColor("#1A1412")
    val red = Color.parseColor("#E5484D")
    val green = Color.parseColor("#46A758")
    val blue = Color.parseColor("#3E8EF7")
    val yellow = Color.parseColor("#F5D90A")
}

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

/** Background that lights up with an amber ring when D-pad focus lands on it. */
fun Context.focusBg(color: Int, radiusDp: Int = 16): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), rounded(color, radiusDp, Palette.accent, 3))
    addState(intArrayOf(android.R.attr.state_pressed), rounded(Palette.track, radiusDp))
    addState(intArrayOf(), rounded(color, radiusDp))
}

fun Context.label(
    text: CharSequence,
    sizeSp: Float = 16f,
    color: Int = Palette.text,
    bold: Boolean = false,
    caps: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = Typeface.create("sans-serif-medium".takeIf { bold } ?: "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    if (caps) {
        setAllCaps(true)
        letterSpacing = 0.08f
    }
    includeFontPadding = false
}

fun Context.icon(res: Int, color: Int = Palette.accent, sizeDp: Int = 26): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.column(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = spacer(gapDp)
    }
}

fun Context.row(gapDp: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) {
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = spacer(gapDp)
    }
}

private fun Context.spacer(gapDp: Int): Drawable = object : ColorDrawable(Color.TRANSPARENT) {
    override fun getIntrinsicWidth() = dp(gapDp)
    override fun getIntrinsicHeight() = dp(gapDp)
}

fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

fun weighted(weight: Float = 1f, heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(0, heightPx, weight)

fun View.clickable(focusable: Boolean = true, onClick: () -> Unit): View {
    isClickable = true
    isFocusable = focusable
    setOnClickListener { onClick() }
    return this
}

/** Horizontal bar 0..100. */
fun Context.bar(percent: Int, fill: Int = Palette.accent, heightDp: Int = 6): View {
    val p = percent.coerceIn(0, 100)
    val track = LinearLayout(this)
    track.orientation = LinearLayout.HORIZONTAL
    track.background = rounded(Palette.track, heightDp / 2)
    if (p > 0) {
        track.addView(View(this).apply { background = rounded(fill, heightDp / 2) }, weighted(p.toFloat(), dp(heightDp)))
    }
    if (p < 100) {
        track.addView(View(this), weighted((100 - p).toFloat(), dp(heightDp)))
    }
    track.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    return track
}

/** A chip / pill button. */
fun Context.pill(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(text, 14f, if (selected) Palette.ground else Palette.soft, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        background = if (selected) StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Palette.accent, 12, Palette.text, 2))
            addState(intArrayOf(), rounded(Palette.accent, 12))
        } else focusBg(Palette.surface, 12)
        minHeight = dp(44)
        clickable { onClick() }
    }

/** Section heading in small caps. */
fun Context.section(text: String): TextView = label(text, 12f, Palette.muted, bold = true, caps = true)

/** Square-ish button with a label, used for custom keys and scenes. */
fun Context.keyButton(text: String, active: Boolean = false, onClick: () -> Unit): TextView =
    label(text, 15f, if (active) Palette.ground else Palette.text, bold = active).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(dp(6), 0, dp(6), 0)
        background = focusBg(if (active) Palette.accent else Palette.raised, 14)
        minHeight = dp(52)
        clickable { onClick() }
    }
