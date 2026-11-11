package com.arsmarthome.remote

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * The Halo moment: while this screen is up, the D-pad, OK, BACK, MAIN and
 * transport keys drive the activity's devices. The touchscreen keeps the
 * source switcher, custom buttons and volume.
 */
class ActivityScreen(host: Host, val activityId: String) : Screen(host) {
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
        val head = header("${room.name} · ${if (act.kind == "listen") "Listen" else "Watch"}", act.name, back)
        if (act.kind != "listen" && running && head is LinearLayout) {
            // Playing / Paused / On, as the source reports it.
            val st = live.state(act.source).ifEmpty { "on" }.let { if (it in setOf("playing", "paused")) it else "on" }
            head.addView(ctx.label(st, 12f, Palette.accent, bold = true, caps = true).apply {
                background = ctx.rounded(0xFF2A2113.toInt(), 14)
                setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            })
        }
        parts += head

        if (!running) {
            parts += ctx.keyButton("Start ${act.name}", active = true) { host.startRoomActivity(act) }.apply { isFocusable = false }
        }

        if (act.kind == "listen") {
            parts += listenPlayer(act)
            return page(*parts.toTypedArray(), scroll = true)
        }

        // ---- Picture: artwork with the title over it, else the activity's icon / logo.
        val src = live.get(act.source)
        val title = src?.optStr("t")
        val sub = src?.optStr("a") ?: src?.optStr("src")
        val art = Artwork.get(src?.optStr("pic")) { host.refresh() }
        val hero = android.widget.FrameLayout(ctx).apply {
            background = ctx.rounded(Palette.surface, 24)
            clipToOutline = true
        }
        if (art != null) {
            hero.addView(android.widget.ImageView(ctx).apply {
                setImageBitmap(art)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }, android.widget.FrameLayout.LayoutParams(-1, -1))
            // Dark fade at the bottom so the title reads on any picture.
            hero.addView(View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xE60E1116.toInt(), 0x000E1116),
                )
            }, android.widget.FrameLayout.LayoutParams(-1, ctx.dp(110), Gravity.BOTTOM))
            val text = ctx.column(2).apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(14)) }
            text.addView(ctx.label(title ?: act.name, 20f, bold = true).apply { maxLines = 2 })
            if (!sub.isNullOrEmpty()) text.addView(ctx.label(sub, 14f, Palette.soft).apply { maxLines = 1 })
            hero.addView(text, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        } else {
            val col = ctx.column(6).apply { gravity = Gravity.CENTER }
            val lib = ctx.libraryIcon(act.logo)
            val logo = if (lib == 0) host.logoCut(act) else null
            col.addView(
                when {
                    logo != null -> ctx.homeGlyph(logo, R.drawable.ic_watch, false, 104)
                    lib != 0 -> ctx.homeGlyph(null, lib, running, if (isRealIcon(act.logo)) 108 else 72, tint = !isRealIcon(act.logo))
                    else -> ctx.homeGlyph(null, R.drawable.ic_watch, running, 64)
                }
            )
            if (title != null) col.addView(ctx.label(title, 17f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1 })
            if (!sub.isNullOrEmpty()) col.addView(ctx.label(sub, 14f, Palette.soft).apply { gravity = Gravity.CENTER; maxLines = 1 })
            hero.addView(col, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        parts += hero.apply { layoutParams = LinearLayout.LayoutParams(-1, ctx.dp(184)) }

        // ---- Transport: big round keys, play / pause in the accent colour.
        val transport = ctx.row(22).apply { gravity = Gravity.CENTER }
        listOf(
            Triple(R.drawable.ic_prev, "previous", "Previous"),
            Triple(R.drawable.ic_playpause, "play_pause", "Play / pause"),
            Triple(R.drawable.ic_next, "next", "Next"),
        ).forEach { (res, action, desc) ->
            val main = action == "play_pause"
            val size = if (main) 80 else 62
            val b = ctx.icon(res, if (main) Palette.ground else Palette.text, if (main) 30 else 24).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                background = ctx.focusBg(if (main) Palette.accent else Palette.surface, size / 2)
                if (main) elevation = ctx.dp(6).toFloat()
                contentDescription = desc
                clickable(focusable = false) { host.send("media", "entity" to act.source, "action" to action) }
            }
            transport.addView(b, LinearLayout.LayoutParams(ctx.dp(size), ctx.dp(size)))
        }
        parts += transport

        // ---- Volume: one pill — minus, level, plus, mute.
        val vol = live.get(act.volume)
        val level = vol?.optInt("v", -1) ?: -1
        val muted = vol?.optBoolean("m", false) ?: false
        val volRow = ctx.row(10).apply {
            background = ctx.rounded(Palette.ground, 32, Palette.track, 1)
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }
        volRow.addView(volKey(R.drawable.ic_minus, "Volume down", false) { host.send("media", "entity" to act.volume, "action" to "volume_down") })
        val mid = ctx.column(6)
        val top = ctx.row()
        top.addView(ctx.label(nameOf(act.volume) ?: "Volume", 12f, Palette.muted).apply { maxLines = 1 }, weighted())
        top.addView(ctx.label(if (muted) "Muted" else if (level < 0) "—" else level.toString(), 15f, if (muted) Palette.accent else Palette.text, bold = true))
        mid.addView(top, matchWrap())
        mid.addView(ctx.bar(if (level < 0) 0 else level, if (muted) Palette.muted else Palette.text, 8), matchWrap())
        volRow.addView(mid, weighted())
        volRow.addView(volKey(R.drawable.ic_plus, "Volume up", false) { host.send("media", "entity" to act.volume, "action" to "volume_up") })
        volRow.addView(volKey(R.drawable.ic_volume, if (muted) "Unmute" else "Mute", muted) { host.send("media", "entity" to act.volume, "action" to "mute") })
        parts += volRow

        // ---- Shortcuts: the lights, the aircon, another source — without leaving Watch.
        // Each opens its page and comes back here; none of them re-runs the activity.
        val device = host.project?.device ?: DeviceSettings()
        val here: () -> Screen = { ActivityScreen(host, act.id) }
        val shortcuts = mutableListOf<View>()
        if (room.lights.isNotEmpty() || room.scenes.isNotEmpty()) {
            val on = room.lights.count { live.isOn(it.entity) }
            shortcuts += shortcut(device, "lights", R.drawable.ic_light, "Lights", if (on == 0) "All off" else "$on on", on > 0) {
                host.show(LightsScreen(host, here))
            }
        }
        room.climate?.let { c ->
            val st = live.get(c.entity)
            val isOff = live.state(c.entity) == "off" || st == null
            val text = when {
                st == null -> "—"
                isOff -> "Off"
                st.has("t") && !st.isNull("t") -> "${"%.0f".format(st.optDouble("t"))}°"
                else -> "On"
            }
            shortcuts += shortcut(device, "comfort", R.drawable.ic_comfort, "Comfort", text, !isOff) {
                host.show(ComfortScreen(host, here))
            }
        }
        if (siblings.size > 1) {
            shortcuts += shortcut(device, "watch", R.drawable.ic_watch, "Switch", "${siblings.size} sources", false) {
                host.show(SourcesScreen(host, act.kind))
            }
        }
        if (shortcuts.isNotEmpty()) parts += grid(shortcuts, shortcuts.size, 10)

        // ---- Custom buttons
        val custom = act.custom.mapIndexedNotNull { i, label ->
            if (label.isBlank()) null else ctx.keyButton(label) {
                host.send("slot", "activity" to act.id, "slot" to "custom${i + 1}")
            }.apply { isFocusable = false }
        }
        if (custom.isNotEmpty()) parts += grid(custom, 3, 8)

        // ---- Colour keys: what each one does, with its colour under it.
        val colors = listOf(
            "red" to Palette.red, "green" to Palette.green, "blue" to Palette.blue, "yellow" to Palette.yellow,
        )
        if (act.colors.values.any { it.isNotBlank() }) {
            val keys = colors.map { (slot, color) ->
                val c = ctx.column().apply {
                    background = ctx.rounded(Palette.surface, 14)
                    clipToOutline = true
                    contentDescription = act.colors[slot].orEmpty()
                    clickable(focusable = false) { host.send("slot", "activity" to act.id, "slot" to slot) }
                }
                c.addView(ctx.label(act.colors[slot].orEmpty().ifBlank { "—" }, 12f, Palette.text, bold = true).apply {
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
                }, LinearLayout.LayoutParams(-1, ctx.dp(42)))
                c.addView(View(ctx).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(-1, ctx.dp(4)))
                c
            }
            parts += grid(keys, 4, 8)
        }

        // ---- Which device the remote's hard keys drive on this screen.
        val keysHint = buildString {
            if (act.hasDpad) append("D-pad · OK · Back: ${act.name}")
            val v = nameOf(act.volume)
            if (v != null) { if (isNotEmpty()) append("   ·   "); append("Volume: $v") }
        }
        if (keysHint.isNotEmpty()) parts += ctx.label(keysHint, 12f, Palette.muted).apply { gravity = Gravity.CENTER; maxLines = 1 }

        parts += roomOffButton()
        return page(*parts.toTypedArray(), scroll = true)
    }

    /** A round key on the volume pill; [lit] = muted. */
    private fun volKey(res: Int, desc: String, lit: Boolean, onClick: () -> Unit): View =
        ctx.icon(res, if (lit) Palette.accent else Palette.text, 22).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = ctx.focusBg(Palette.raised, 25)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(50), ctx.dp(50))
            contentDescription = desc
            clickable(focusable = false) { onClick() }
        }

    /** Lights / Comfort / Switch: the room's own icon for it, name and status. */
    private fun shortcut(device: DeviceSettings, key: String, fallback: Int, title: String, sub: String, active: Boolean, onClick: () -> Unit): View {
        val col = ctx.column(2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ctx.focusBg(Palette.surface, 18)
            setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
            contentDescription = "$title, $sub"
            clickable(focusable = false) { onClick() }
        }
        val chosen = homeIconValue(device, key)
        val lib = ctx.libraryIcon(chosen)
        val real = lib != 0 && isRealIcon(chosen)
        val custom = if (lib == 0) host.image(chosen) else null
        col.addView(ctx.homeGlyph(custom, if (lib != 0) lib else fallback, active, if (real || custom != null) 38 else 28, tint = !real))
        col.addView(ctx.label(title, 13f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, ctx.dp(4), 0, 0) }, matchWrap())
        col.addView(ctx.label(sub, 11f, if (active) Palette.accent else Palette.muted).apply { gravity = Gravity.CENTER; maxLines = 1 }, matchWrap())
        return col
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

        out += roomOffButton()
        return out
    }

    /** Ends the room right here (everything off) and goes back to Rooms. */
    private fun roomOffButton(): View = ctx.row(10).apply {
        gravity = Gravity.CENTER
        background = ctx.focusBg(Palette.dangerBg, 16)
        minimumHeight = ctx.dp(56)
        addView(ctx.icon(R.drawable.ic_power, Palette.danger, 20))
        addView(ctx.label("Room Off", 17f, Palette.danger, bold = true))
        contentDescription = "Room Off"
        clickable(focusable = false) { host.askRoomOff() }
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
}
