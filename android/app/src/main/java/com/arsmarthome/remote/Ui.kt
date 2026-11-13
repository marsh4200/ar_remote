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

/**
 * The app's colours. Two looks, chosen in Home Assistant (Home screen icons → Look):
 * Classic (flat dark, amber) and Residence (a photo behind glass cards, champagne).
 * Screens read these while they are built, so [use] followed by a redraw switches look.
 */
object Palette {
    var ground = Color.parseColor("#0E1116")
        private set
    var surface = Color.parseColor("#171B22")
        private set
    var raised = Color.parseColor("#1B2029")
        private set
    var track = Color.parseColor("#2A313C")
        private set
    var text = Color.parseColor("#F3F4F6")
        private set
    var soft = Color.parseColor("#C9CED6")
        private set
    var muted = Color.parseColor("#9AA3AF")
        private set
    var accent = Color.parseColor("#F2A541")
        private set
    var danger = Color.parseColor("#F9A487")
        private set
    var dangerBg = Color.parseColor("#1A1412")
        private set
    /** A sunken strip (the volume pill): the page colour in Classic, dark glass in Residence. */
    var well = Color.parseColor("#0E1116")
        private set
    /** True in the Residence look. */
    var residence = false
        private set

    fun use(residence: Boolean) {
        this.residence = residence
        if (residence) {
            ground = Color.parseColor("#121013")
            surface = Color.parseColor("#8C161418") // glass: see-through, the photo shows behind
            raised = Color.parseColor("#99161418")
            track = Color.parseColor("#33FFFFFF")
            text = Color.parseColor("#F1EDE6")
            soft = Color.parseColor("#D6CFC4")
            muted = Color.parseColor("#B8B0A4")
            accent = Color.parseColor("#F3D39A")
            danger = Color.parseColor("#F9A487")
            dangerBg = Color.parseColor("#99281210")
            well = Color.parseColor("#66000000")
        } else {
            ground = Color.parseColor("#0E1116")
            surface = Color.parseColor("#171B22")
            raised = Color.parseColor("#1B2029")
            track = Color.parseColor("#2A313C")
            text = Color.parseColor("#F3F4F6")
            soft = Color.parseColor("#C9CED6")
            muted = Color.parseColor("#9AA3AF")
            accent = Color.parseColor("#F2A541")
            danger = Color.parseColor("#F9A487")
            dangerBg = Color.parseColor("#1A1412")
            well = Color.parseColor("#0E1116")
        }
    }

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
    typeface = when {
        !Palette.residence ->
            Typeface.create("sans-serif-medium".takeIf { bold } ?: "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        // Residence: big titles go light, small headings medium, nothing heavy.
        bold && sizeSp >= 20f -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
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

/** The lit round "bubble" behind a home icon (C4-style). Amber ring when [active]. */
fun Context.bubble(active: Boolean, sizeDp: Int): Drawable = if (Palette.residence) GradientDrawable().apply {
    // Residence: a glass ring, champagne when active.
    shape = GradientDrawable.OVAL
    setColor(if (active) tinted(Palette.accent, 0x29) else Color.parseColor("#6B141216"))
    setStroke(dp(1), if (active) tinted(Palette.accent, 0xD9) else Color.parseColor("#47FFFFFF"))
} else GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    gradientType = GradientDrawable.RADIAL_GRADIENT
    gradientRadius = dp(sizeDp) * 0.75f
    setGradientCenter(0.35f, 0.28f)
    colors = if (active) {
        intArrayOf(Color.parseColor("#3A3326"), Color.parseColor("#221E17"), Color.parseColor("#15130F"))
    } else {
        intArrayOf(Color.parseColor("#2C333E"), Color.parseColor("#1B2029"), Color.parseColor("#12161C"))
    }
    if (active) setStroke(dp(2), Palette.accent) else setStroke(dp(1), Color.parseColor("#262C36"))
}

/** [color] at [alpha] 0..255. */
fun tinted(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

/** Residence, icons only: the soft light behind an icon that is on. No edge, it fades out. */
fun Context.glow(sizeDp: Int): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    gradientType = GradientDrawable.RADIAL_GRADIENT
    gradientRadius = dp(sizeDp) / 2f
    colors = intArrayOf(tinted(Palette.accent, 0x55), tinted(Palette.accent, 0x00))
}

/**
 * A home icon: the installer's own image when one is set (shown as it is),
 * else the built-in vector, accent-coloured when [active].
 */
fun Context.homeGlyph(custom: android.graphics.Bitmap?, res: Int, active: Boolean, sizeDp: Int, tint: Boolean = true): ImageView =
    ImageView(this).apply {
        if (custom != null) {
            setImageBitmap(custom)
            scaleType = ImageView.ScaleType.FIT_CENTER
        } else {
            setImageResource(res)
            if (tint) imageTintList = ColorStateList.valueOf(if (active) Palette.accent else Palette.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

/**
 * A built-in library icon, or 0 when [value] isn't one:
 * "icon:real_tv" → drawable real_tv (realistic, full colour), "icon:tv" → drawable lib_tv (line),
 * "icon:look_tv" → drawable look_tv (the Residence look's own icons).
 */
fun Context.libraryIcon(value: String?): Int {
    if (value == null || !value.startsWith("icon:")) return 0
    val name = value.removePrefix("icon:").filter { it.isLetterOrDigit() || it == '_' }
    if (name.isEmpty()) return 0
    val full = name.startsWith("real_") || name.startsWith("look_")
    return resources.getIdentifier(if (full) name else "lib_$name", "drawable", packageName)
}

/** A realistic library icon: drawn in its own colours, never tinted. */
fun isRealIcon(value: String?): Boolean =
    value != null && (value.startsWith("icon:real_") || value.startsWith("icon:look_"))

private val REALISTIC_HOME = mapOf(
    "watch" to "real_tv", "listen" to "real_speaker", "lights" to "real_bulb", "comfort" to "real_aircon",
    "shades" to "real_blinds", "favourites" to "real_star", "room_off" to "real_power",
)

/** The Residence look's icons (glowing bulb, glass TV …); Room Off keeps the red power button. */
private val RESIDENCE_HOME = mapOf(
    "watch" to "look_tv", "listen" to "look_speaker", "lights" to "look_bulb", "comfort" to "look_aircon",
    "shades" to "look_blinds", "favourites" to "look_sparkle", "room_off" to "real_power",
)

/** The icon for a home spot: the installer's pick, else the realistic set's one (when that set is on). */
fun homeIconValue(device: DeviceSettings, key: String): String? =
    device.icons[key] ?: if (device.iconSet == "realistic") {
        (if (device.look == "residence") RESIDENCE_HOME else REALISTIC_HOME)[key]?.let { "icon:$it" }
    } else null
