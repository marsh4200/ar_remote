package com.arsmarthome.remote

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The Halo moment: while this screen is up, the D-pad, OK, BACK, MAIN and
 * transport keys drive the activity's devices. The touchscreen keeps the
 * source switcher, custom buttons and volume.
 */
class ActivityScreen(host: Host, private val activityId: String) : Screen(host) {
    override val name = "activity"
    override val routesToDevice = true

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val act = room.activity(activityId) ?: return page(header(room.name, "Activity"), empty("This activity was removed."))
        val live = host.live
        val running = host.activeActivity()?.id == act.id
        val parts = mutableListOf<View>()

        // No switching between activities here: back goes to the Watch / Listen
        // picker (or the room when there's only one), and you pick again there.
        val siblings = room.activitiesOf(act.kind)
        val back: () -> Unit = if (siblings.size > 1) {
            { host.show(SourcesScreen(host, act.kind)) }
        } else {
            { host.home() }
        }
        parts += header("${room.name} · ${if (act.kind == "listen") "Listen" else "Watch"}", act.name, back)

        if (!running) {
            parts += ctx.keyButton("Start ${act.name}", active = true) { host.startRoomActivity(act) }.apply { isFocusable = false }
        }

        if (act.kind == "listen") {
            parts += listenPlayer(act)
            return page(*parts.toTypedArray(), scroll = true)
        }

        // Now playing card
        val src = live.get(act.source)
        val card = ctx.column(8).apply {
            background = ctx.rounded(Palette.surface, 20)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        val title = src?.optStr("t") ?: act.name
        val sub = src?.optStr("a") ?: src?.optStr("src") ?: ""
        card.addView(ctx.label(title, 18f, bold = true).apply { maxLines = 2 })
        if (sub.isNotEmpty()) card.addView(ctx.label(sub, 14f, Palette.muted).apply { maxLines = 1 })
        val stateText = live.state(act.source).ifEmpty { if (running) "on" else "off" }
        card.addView(ctx.label(stateText.replaceFirstChar { it.uppercase() }, 13f, Palette.accent))
        val transport = ctx.row(10)
        listOf(
            Triple(R.drawable.ic_prev, "previous", "Previous"),
            Triple(R.drawable.ic_playpause, "play_pause", "Play / pause"),
            Triple(R.drawable.ic_next, "next", "Next"),
        ).forEach { (res, action, desc) ->
            val b = ctx.icon(res, Palette.text, 22).apply {
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                background = ctx.focusBg(Palette.raised, 12)
                contentDescription = desc
                clickable(focusable = false) { host.send("media", "entity" to act.source, "action" to action) }
            }
            transport.addView(b, weighted(1f, ctx.dp(44)))
        }
        card.addView(transport, matchWrap())
        parts += card

        // Which device the hard buttons drive
        val routing = ctx.column(6).apply {
            background = ctx.rounded(Palette.ground, 14, Palette.track, 1)
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        }
        routing.addView(ctx.section("Hard buttons control"))
        routing.addView(routeRow("D-pad · OK · Back", if (act.hasDpad) act.name else "—"))
        routing.addView(routeRow("Volume · Mute", nameOf(act.volume) ?: act.name))
        routing.addView(routeRow("Power", "Room off"))
        parts += routing

        // Custom buttons
        val custom = act.custom.mapIndexedNotNull { i, label ->
            if (label.isBlank()) null else ctx.keyButton(label) {
                host.send("slot", "activity" to act.id, "slot" to "custom${i + 1}")
            }.apply { isFocusable = false }
        }
        if (custom.isNotEmpty()) {
            parts += ctx.section("Custom buttons")
            parts += grid(custom, 3, 8)
        }

        // Colour keys legend
        val colors = listOf(
            "red" to Palette.red, "green" to Palette.green, "blue" to Palette.blue, "yellow" to Palette.yellow,
        )
        if (act.colors.values.any { it.isNotBlank() }) {
            parts += ctx.section("Colour keys")
            val keys = colors.map { (slot, color) ->
                val c = ctx.column(6).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    clickable(focusable = false) { host.send("slot", "activity" to act.id, "slot" to slot) }
                }
                c.addView(View(ctx).apply { background = ctx.rounded(color, 3) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(6)))
                c.addView(ctx.label(act.colors[slot].orEmpty().ifBlank { "—" }, 12f, Palette.soft).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                })
                c
            }
            parts += grid(keys, 4, 8)
        }

        // Volume
        val vol = live.get(act.volume)
        val volRow = ctx.row(10)
        volRow.addView(roundIcon(R.drawable.ic_minus, "Volume down") { host.send("media", "entity" to act.volume, "action" to "volume_down") })
        val level = vol?.optInt("v", -1) ?: -1
        val muted = vol?.optBoolean("m", false) ?: false
        volRow.addView(ctx.bar(if (level < 0) 0 else level, if (muted) Palette.muted else Palette.text, 8), weighted())
        volRow.addView(roundIcon(R.drawable.ic_plus, "Volume up") { host.send("media", "entity" to act.volume, "action" to "volume_up") })
        volRow.addView(ctx.label(if (muted) "Mute" else if (level < 0) "—" else level.toString(), 15f, bold = true).apply {
            minWidth = ctx.dp(40)
            gravity = Gravity.END
            clickable(focusable = false) { host.send("media", "entity" to act.volume, "action" to "mute") }
        })
        parts += volRow

        return page(*parts.toTypedArray(), scroll = true)
    }

    /** Listen: big artwork, big text, big buttons — readable at arm's length. */
    private fun listenPlayer(act: Activity): List<View> {
        val live = host.live
        val src = live.get(act.source)
        val out = mutableListOf<View>()

        val side = minOf(ctx.resources.displayMetrics.widthPixels - ctx.dp(80), ctx.dp(300))
        val bmp = Artwork.get(src?.optStr("pic")) { host.refresh() }
        val art = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = ctx.rounded(Palette.raised, 24)
            if (bmp != null) {
                setImageBitmap(bmp)
            } else {
                setImageResource(R.drawable.ic_listen)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.muted)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(side / 3, side / 3, side / 3, side / 3)
            }
        }
        val artWrap = android.widget.FrameLayout(ctx)
        artWrap.addView(art, android.widget.FrameLayout.LayoutParams(side, side, Gravity.CENTER_HORIZONTAL))
        out += artWrap

        val text = ctx.column(6).apply { gravity = Gravity.CENTER_HORIZONTAL }
        text.addView(ctx.label(src?.optStr("t") ?: act.name, 24f, bold = true).apply {
            gravity = Gravity.CENTER; maxLines = 2
        })
        (src?.optStr("a") ?: src?.optStr("src"))?.let {
            text.addView(ctx.label(it, 17f, Palette.soft).apply { gravity = Gravity.CENTER; maxLines = 1 })
        }
        val st = live.state(act.source).ifEmpty { "off" }
        text.addView(ctx.label(st.replace('_', ' '), 13f, Palette.accent, bold = true, caps = true).apply { gravity = Gravity.CENTER })
        out += text

        val transport = ctx.row(12)
        listOf(
            Triple(R.drawable.ic_prev, "previous", "Previous"),
            Triple(R.drawable.ic_playpause, "play_pause", "Play / pause"),
            Triple(R.drawable.ic_next, "next", "Next"),
        ).forEach { (res, action, desc) ->
            val main = action == "play_pause"
            val b = ctx.icon(res, if (main) Palette.ground else Palette.text, 30).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                background = ctx.focusBg(if (main) Palette.accent else Palette.raised, 20)
                contentDescription = desc
                clickable(focusable = false) { host.send("media", "entity" to act.source, "action" to action) }
            }
            transport.addView(b, weighted(1f, ctx.dp(68)))
        }
        out += transport

        val custom = act.custom.mapIndexedNotNull { i, label ->
            if (label.isBlank()) null else ctx.keyButton(label) {
                host.send("slot", "activity" to act.id, "slot" to "custom${i + 1}")
            }.apply { isFocusable = false }
        }
        if (custom.isNotEmpty()) out += grid(custom, 3, 8)

        val vol = live.get(act.volume)
        val level = vol?.optInt("v", -1) ?: -1
        val muted = vol?.optBoolean("m", false) ?: false
        val volRow = ctx.row(12)
        volRow.addView(bigRound(R.drawable.ic_minus, "Volume down") { host.send("media", "entity" to act.volume, "action" to "volume_down") })
        volRow.addView(ctx.bar(if (level < 0) 0 else level, if (muted) Palette.muted else Palette.text, 12), weighted())
        volRow.addView(bigRound(R.drawable.ic_plus, "Volume up") { host.send("media", "entity" to act.volume, "action" to "volume_up") })
        volRow.addView(ctx.label(if (muted) "Mute" else if (level < 0) "—" else level.toString(), 22f, bold = true).apply {
            minWidth = ctx.dp(48)
            gravity = Gravity.END
            clickable(focusable = false) { host.send("media", "entity" to act.volume, "action" to "mute") }
        })
        out += volRow

        out += ctx.row(10).apply {
            gravity = Gravity.CENTER
            background = ctx.focusBg(Palette.dangerBg, 16)
            minimumHeight = ctx.dp(56)
            addView(ctx.icon(R.drawable.ic_power, Palette.danger, 20))
            addView(ctx.label("Room Off", 17f, Palette.danger, bold = true))
            clickable(focusable = false) { host.roomOff() }
        }
        return out
    }

    private fun bigRound(res: Int, desc: String, onClick: () -> Unit): View =
        ctx.icon(res, Palette.text, 24).apply {
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            background = ctx.focusBg(Palette.surface, 28)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56))
            contentDescription = desc
            clickable(focusable = false) { onClick() }
        }

    private fun nameOf(entity: String?): String? {
        val room = host.room ?: return null
        return room.activities.firstOrNull { it.source == entity }?.name
            ?: entity?.substringAfter('.')?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
    }

    private fun routeRow(left: String, right: String): View = ctx.row().apply {
        addView(ctx.label(left, 14f, Palette.soft), weighted())
        addView(ctx.label(right, 14f).apply { maxLines = 1 })
    }

    private fun roundIcon(res: Int, desc: String, onClick: () -> Unit): View =
        ctx.icon(res, Palette.text, 20).apply {
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            background = ctx.focusBg(Palette.surface, 22)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
            contentDescription = desc
            clickable(focusable = false) { onClick() }
        }
}
