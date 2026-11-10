package com.arsmarthome.remote

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * "Turn the Lounge off?" — what turns off, the client's choices (lights, aircon …)
 * with a toggle each, and what stays on for a few minutes. The power key never
 * shows this: it turns the room off straight away with the defaults.
 */
class RoomOffScreen(host: Host) : Screen(host) {
    override val name = "room_off"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    /** id → turn it off. Kept on this screen object, so a state push doesn't reset it. */
    private val picked = HashMap<String, Boolean>()

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val off = room.off
        val act = host.activeActivity()
        val parts = mutableListOf<View>()

        parts += header(if (act != null) "${act.name} is playing" else room.name, "Turn ${room.name} off?", back = { host.home() })

        if (off.turnsOff.isNotEmpty()) {
            parts += group("Turns off", Palette.danger)
            parts += ctx.label(off.turnsOff.joinToString("  ·  "), 15f, Palette.soft).apply {
                background = ctx.rounded(Palette.surface, 16)
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
                setLineSpacing(0f, 1.25f)
            }
        }

        if (off.choices.isNotEmpty()) {
            parts += group("Your choice", Palette.accent)
            val list = ctx.column().apply { background = ctx.rounded(Palette.surface, 16) }
            off.choices.forEachIndexed { i, c ->
                if (i > 0) list.addView(View(ctx).apply { setBackgroundColor(Palette.track) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
                list.addView(choiceRow(c), matchWrap())
            }
            parts += list
        }

        if (off.staysOn.isNotEmpty()) {
            parts += group("Stays on for a bit", Color.parseColor("#5EC2E8"))
            val col = ctx.column(8).apply {
                background = ctx.rounded(Palette.surface, 16)
                setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            }
            off.staysOn.forEach { (name, minutes) ->
                val row = ctx.row(10)
                row.addView(ctx.label(name, 15f), weighted())
                row.addView(ctx.label(minutesText(minutes), 14f, Palette.muted))
                col.addView(row, matchWrap())
            }
            parts += col
        }

        val buttons = ctx.row(10)
        buttons.addView(ctx.label("Cancel", 16f, Palette.text, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(56)
            background = ctx.focusBg(Palette.raised, 28)
            tag = "off-cancel"
            clickable { host.home() }
        }, weighted())
        buttons.addView(ctx.label("Turn off", 16f, Palette.dangerBg, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = ctx.dp(56)
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ctx.rounded(Palette.danger, 28, Palette.text, 3))
                addState(intArrayOf(), ctx.rounded(Palette.danger, 28))
            }
            tag = "off-confirm"
            clickable { host.roomOff(off.choices.associate { it.id to (picked[it.id] ?: it.defaultOff) }) }
        }, weighted())
        parts += buttons
        parts += ctx.label("The power key skips this and uses the saved choices", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
        }
        return page(*parts.toTypedArray(), scroll = true)
    }

    private fun minutesText(m: Double): String =
        if (m < 1) "${(m * 60).toInt()} s" else if (m % 1.0 == 0.0) "${m.toInt()} min" else "${"%.1f".format(m)} min"

    private fun group(text: String, color: Int): View = ctx.row(8).apply {
        addView(View(ctx).apply { background = ctx.rounded(color, 4) }, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)))
        addView(ctx.label(text, 12f, color, bold = true, caps = true))
        setPadding(0, ctx.dp(4), 0, 0)
    }

    /** Name + hint on the left, a toggle on the right: on = it turns off. */
    private fun choiceRow(c: OffChoice): View {
        val turnOff = picked[c.id] ?: c.defaultOff
        val row = ctx.row(12).apply {
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            minimumHeight = ctx.dp(60)
            background = ctx.focusBg(Color.TRANSPARENT, 16)
            tag = "choice-${c.id}"
            contentDescription = "${c.name}: ${if (turnOff) "turns off" else "stays on"}"
            clickable {
                picked[c.id] = !turnOff
                host.refreshNow()
            }
        }
        val text = ctx.column(2)
        text.addView(ctx.label(c.name, 16f, bold = true))
        text.addView(ctx.label(if (turnOff) "Turns off" else "Stays on", 12f, if (turnOff) Palette.accent else Palette.muted).apply {
            if (c.hint.isNotBlank()) this.text = "${this.text} · ${c.hint}"
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(text, weighted())
        // The toggle: amber track with the knob right = turns off.
        val track = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (turnOff) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
            background = ctx.rounded(if (turnOff) Palette.accent else Palette.track, 15)
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            addView(View(ctx).apply { background = ctx.rounded(if (turnOff) Palette.dangerBg else Palette.muted, 12) },
                LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)))
        }
        row.addView(track, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(30)))
        return row
    }
}
