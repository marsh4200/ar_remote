package com.arsmarthome.remote

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** What every screen can ask of the activity. */
interface Host {
    val ctx: Context
    val project: Project?
    val live: LiveState
    val room: Room?
    fun activeActivity(): Activity?
    fun selectRoom(id: String)
    fun show(screen: Screen)
    fun home()
    /** The main page: Rooms (or the room itself when there is only one). */
    fun mainPage()
    fun send(type: String, vararg fields: Pair<String, Any?>)
    fun startRoomActivity(activity: Activity)
    fun roomOff()
    fun refresh()
    fun refreshNow() = refresh()
    /** An activity's logo (null until loaded, or when it has none). */
    fun logoOf(activity: Activity): android.graphics.Bitmap? = null
}

abstract class Screen(protected val host: Host) {
    protected val ctx: Context get() = host.ctx

    /** Short name reported to HA. */
    abstract val name: String

    /** Where BACK goes when the screen doesn't route keys to a device. */
    open val parent: (() -> Screen)? = null

    /**
     * Pages with text entry or live controls (settings panel, Wi-Fi, PIN): state
     * pushes from HA don't rebuild them (that would wipe what you're typing).
     */
    open val holdsInput: Boolean = false

    /** True on activity screens: D-pad, BACK, MAIN and transport drive the device. */
    open val routesToDevice: Boolean = false

    abstract fun build(): View

    /** Screen-specific handling (e.g. dimming with LEFT/RIGHT). True = consumed. */
    open fun onKey(key: Key, event: KeyEvent): Boolean = false

    // ------------------------------------------------------------ building

    protected fun page(vararg children: View, scroll: Boolean = false): View {
        val col = ctx.column(14)
        col.setPadding(ctx.dp(16), ctx.dp(18), ctx.dp(16), ctx.dp(16))
        children.forEach { col.addView(it, matchWrap()) }
        if (!scroll) return col
        return ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
        }
    }

    /** Back arrow + subtitle + title. */
    protected fun header(subtitle: String, title: String, back: (() -> Unit)? = { host.home() }): View {
        val row = ctx.row(12)
        if (back != null) {
            val btn = ctx.icon(R.drawable.ic_back, Palette.text, 22).apply {
                setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                background = ctx.focusBg(Palette.surface, 12)
                layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
                contentDescription = "Back"
                clickable { back() }
            }
            row.addView(btn)
        }
        val col = ctx.column(2)
        col.addView(ctx.label(subtitle, 13f, Palette.muted))
        col.addView(ctx.label(title, 22f, bold = true).apply { maxLines = 1 })
        row.addView(col, weighted())
        return row
    }

    protected fun empty(text: String): View = ctx.label(text, 15f, Palette.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, ctx.dp(40), 0, ctx.dp(40))
    }

    /** Lay views out in an N-column grid of rows. */
    protected fun grid(views: List<View>, columns: Int, gapDp: Int = 10, rowHeightDp: Int? = null): View {
        val col = ctx.column(gapDp)
        views.chunked(columns).forEach { chunk ->
            val r = ctx.row(gapDp)
            r.gravity = Gravity.FILL_VERTICAL
            chunk.forEach { v ->
                r.addView(v, weighted(1f, rowHeightDp?.let { ctx.dp(it) } ?: LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            repeat(columns - chunk.size) { r.addView(View(ctx), weighted(1f, 1)) }
            col.addView(r, matchWrap())
        }
        return col
    }
}
