package com.arsmarthome.remote

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Room home: header, now-playing strip, the room's icons (C4-style), Room Off. */
class HomeScreen(host: Host) : Screen(host) {
    override val name = "home"
    private val manyRooms get() = (host.project?.rooms?.size ?: 0) > 1
    override val parent: (() -> Screen)? get() = if (manyRooms) ({ RoomsScreen(host) }) else null

    private val device get() = host.project?.device ?: DeviceSettings()
    private val iconsOnly get() = device.homeStyle == "icons"

    override fun build(): View {
        val room = host.room ?: return page(empty("No rooms programmed yet.\nAdd rooms in Home Assistant → AR Remote → Configure."))
        val live = host.live

        // Header: back to Rooms + room picker + temperature
        val header = ctx.row(10)
        if (manyRooms) {
            header.addView(ctx.icon(R.drawable.ic_back, Palette.text, 22).apply {
                setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                background = ctx.focusBg(Palette.surface, 22)
                layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
                contentDescription = "Rooms"
                tag = "back-rooms"
                clickable { host.show(RoomsScreen(host)) }
            })
        }
        val picker = ctx.row(8).apply {
            addView(ctx.label(room.name, 26f, bold = true).apply { maxLines = 1 })
            addView(ctx.icon(R.drawable.ic_down, Palette.muted, 22))
            background = ctx.focusBg(Palette.ground, 10)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            tag = "room-picker"
            contentDescription = "Change room"
            clickable { host.show(RoomsScreen(host)) }
        }
        header.addView(picker, weighted())
        room.temperature?.let { sensor ->
            val s = live.state(sensor).toDoubleOrNull()
            if (s != null) header.addView(ctx.label("${"%.0f".format(s)}°", 20f, Palette.text, bold = true))
        }

        val parts = mutableListOf<View>(header)
        host.activeActivity()?.let { act -> parts += nowPlaying(act) }

        // The room's icons
        val watch = room.activitiesOf("watch")
        val listen = room.activitiesOf("listen")
        val active = host.activeActivity()
        val tiles = mutableListOf<View>()
        val specs = mutableListOf<Spec>()
        if (watch.isNotEmpty()) specs += Spec("watch", R.drawable.ic_watch, "Watch",
            if (active?.kind == "watch") active.name else countText(watch.size, "source"), active?.kind == "watch") {
            openKind(watch, "watch")
        }
        if (listen.isNotEmpty()) specs += Spec("listen", R.drawable.ic_listen, "Listen",
            if (active?.kind == "listen") active.name else countText(listen.size, "source"), active?.kind == "listen") {
            openKind(listen, "listen")
        }
        if (room.lights.isNotEmpty() || room.scenes.isNotEmpty()) {
            val on = room.lights.count { live.isOn(it.entity) }
            specs += Spec("lights", R.drawable.ic_light, "Lights", if (on == 0) "All off" else "$on on", on > 0) {
                host.show(LightsScreen(host))
            }
        }
        room.climate?.let { c ->
            val st = live.get(c.entity)
            val off = live.state(c.entity) == "off"
            val sub = when {
                st == null -> c.name
                off -> "Off"
                st.has("t") && !st.isNull("t") -> "${live.state(c.entity).replaceFirstChar { it.uppercase() }} to ${"%.0f".format(st.optDouble("t"))}°"
                else -> live.state(c.entity)
            }
            specs += Spec("comfort", R.drawable.ic_comfort, "Comfort", sub, st != null && !off && live.isOn(c.entity)) {
                host.show(ComfortScreen(host))
            }
        }
        if (room.covers.isNotEmpty()) {
            val open = room.covers.count { live.state(it.entity) == "open" }
            specs += Spec("shades", R.drawable.ic_shades, "Shades", if (open == 0) "Closed" else "$open open", false) {
                host.show(ShadesScreen(host))
            }
        }
        if (room.favourites.isNotEmpty()) {
            specs += Spec("favourites", R.drawable.ic_star, "Favourites", countText(room.favourites.size, "item"), false) {
                host.show(FavouritesScreen(host))
            }
        }

        // Fill the screen: share the height left over (after the header, now-playing
        // strip and Room Off) between the icon rows.
        val columns = if (specs.size <= 4) 2 else 3
        val rows = (specs.size + columns - 1) / columns
        val dm = ctx.resources.displayMetrics
        val screenDp = dm.heightPixels / dm.density
        val used = 18 + 16 + 52 + 14 + // page padding + header + gap
            (if (active != null) 76 + 14 else 0) + // now-playing strip
            14 + 64 + 8 // gap + Room Off + slack so it never scrolls
        val rowDp = if (rows == 0) 150 else ((screenDp - used - 14 * (rows - 1)) / rows).toInt().coerceIn(132, 230)
        // Room for the label and the status line under the icon.
        bubbleDp = (rowDp - 64).coerceIn(68, if (columns == 2) 120 else 100)
        specs.forEach { tiles += tile(it) }
        parts += grid(tiles, columns, 14, rowHeightDp = rowDp)

        // Room off
        val custom = host.image(device.icons["room_off"])
        val off = ctx.row(10).apply {
            gravity = Gravity.CENTER
            background = ctx.focusBg(Palette.dangerBg, 32)
            minimumHeight = ctx.dp(64)
            if (custom != null) addView(ctx.homeGlyph(custom, R.drawable.ic_power, false, 26))
            else addView(ctx.icon(R.drawable.ic_power, Palette.danger, 22))
            addView(ctx.label("Room Off", 18f, Palette.danger, bold = true))
            tag = "room-off"
            contentDescription = "Room Off"
            clickable { host.askRoomOff() }
        }
        parts += off
        return page(*parts.toTypedArray(), scroll = true)
    }

    private class Spec(
        val key: String,
        val res: Int,
        val title: String,
        val sub: String,
        val active: Boolean,
        val onClick: () -> Unit,
    )

    private var bubbleDp = 92

    private fun countText(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun openKind(list: List<Activity>, kind: String) {
        if (list.size == 1) {
            host.startRoomActivity(list[0])
        } else {
            host.show(SourcesScreen(host, kind))
        }
    }

    private fun nowPlaying(act: Activity): View {
        val live = host.live
        val strip = ctx.row(12).apply {
            background = ctx.focusBg(Palette.raised, 20)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            tag = "now-playing"
            clickable { host.show(ActivityScreen(host, act.id)) }
        }
        val logo = host.logoOf(act)
        val badge = if (logo != null) {
            android.widget.ImageView(ctx).apply {
                setImageBitmap(logo)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = ctx.rounded(Palette.ground, 14)
                clipToOutline = true
                setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52))
            }
        } else ctx.icon(if (act.kind == "listen") R.drawable.ic_listen else R.drawable.ic_watch, Palette.ground, 26).apply {
            background = ctx.rounded(Palette.accent, 14)
            setPadding(ctx.dp(13), ctx.dp(13), ctx.dp(13), ctx.dp(13))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52))
        }
        strip.addView(badge)
        val text = ctx.column(3)
        text.addView(ctx.label(if (act.kind == "listen") "Now listening" else "Now watching", 11f, Palette.accent, bold = true, caps = true))
        text.addView(ctx.label(act.name, 17f, bold = true).apply { maxLines = 1 })
        strip.addView(text, weighted())
        val vol = live.get(act.volume)
        if (vol != null && vol.has("v")) {
            strip.addView(ctx.icon(R.drawable.ic_volume, Palette.muted, 18))
            strip.addView(ctx.label(if (vol.optBoolean("m")) "Muted" else vol.optInt("v").toString(), 14f, Palette.muted))
        }
        return strip
    }

    /** Icon (in its bubble, or on its own), name, and a status line. */
    private fun tile(spec: Spec): View {
        val col = ctx.column().apply {
            gravity = Gravity.CENTER
            // No card behind it: only the D-pad focus ring shows.
            background = ctx.focusBg(Color.TRANSPARENT, 24)
            setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), ctx.dp(6))
            tag = "tile-${spec.key}"
            contentDescription = "${spec.title}, ${spec.sub}"
            clickable { spec.onClick() }
        }
        val custom = host.image(device.icons[spec.key])
        if (iconsOnly) {
            // Icon only: bigger glyph, nothing behind it.
            val size = (bubbleDp * 0.72).toInt()
            col.addView(ctx.homeGlyph(custom, spec.res, spec.active, size))
        } else {
            val bubble = FrameLayout(ctx).apply {
                background = ctx.bubble(spec.active, bubbleDp)
                elevation = ctx.dp(6).toFloat()
            }
            val glyph = ctx.homeGlyph(custom, spec.res, spec.active, if (custom != null) (bubbleDp * 0.62).toInt() else (bubbleDp * 0.44).toInt())
            bubble.addView(glyph, FrameLayout.LayoutParams(glyph.layoutParams.width, glyph.layoutParams.height, Gravity.CENTER))
            col.addView(bubble, LinearLayout.LayoutParams(ctx.dp(bubbleDp), ctx.dp(bubbleDp)))
        }
        col.addView(ctx.label(spec.title, if (bubbleDp >= 96) 18f else 16f, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(0, ctx.dp(10), 0, 0)
        }, matchWrap())
        col.addView(ctx.label(spec.sub, 13f, if (spec.active) Palette.accent else Palette.muted).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(3), 0, 0)
        }, matchWrap())
        return col
    }
}
