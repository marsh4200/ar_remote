package com.arsmarthome.remote

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Room home: header, now-playing strip, activity tiles, Room Off. */
class HomeScreen(host: Host) : Screen(host) {
    override val name = "home"
    private val manyRooms get() = (host.project?.rooms?.size ?: 0) > 1
    override val parent: (() -> Screen)? get() = if (manyRooms) ({ RoomsScreen(host) }) else null

    override fun build(): View {
        val room = host.room ?: return page(empty("No rooms programmed yet.\nAdd rooms in Home Assistant → AR Remote → Configure."))
        val live = host.live

        // Header: back to Rooms + room picker + temperature
        val header = ctx.row(10)
        if (manyRooms) {
            header.addView(ctx.icon(R.drawable.ic_back, Palette.text, 22).apply {
                setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                background = ctx.focusBg(Palette.surface, 12)
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
            if (s != null) header.addView(ctx.label("${"%.0f".format(s)}°", 16f, Palette.muted))
        }

        val parts = mutableListOf<View>(header)

        // Now playing
        host.activeActivity()?.let { act ->
            parts += nowPlaying(act)
        }

        // Fill the screen: share the height left over (after the header, now-playing
        // strip and Room Off) between the tile rows. Few tiles = big tiles; lots of
        // tiles keep the normal size and the page scrolls.
        val tileCount = listOf(
            room.activitiesOf("watch").isNotEmpty(),
            room.activitiesOf("listen").isNotEmpty(),
            room.lights.isNotEmpty() || room.scenes.isNotEmpty(),
            room.climate != null,
            room.covers.isNotEmpty(),
            room.favourites.isNotEmpty(),
        ).count { it }
        val dm = ctx.resources.displayMetrics
        val screenDp = dm.heightPixels / dm.density
        val rows = (tileCount + 1) / 2
        val used = 18 + 16 + 52 + 14 + // page padding + header + gap
            (if (host.activeActivity() != null) 72 + 14 else 0) + // now-playing strip
            14 + 64 + 8 // gap + Room Off + a little slack so it never scrolls
        tileHeightDp = if (rows == 0) 118 else
            ((screenDp - used - 12 * (rows - 1)) / rows).toInt().coerceIn(118, 260)

        // Tiles
        val tiles = mutableListOf<View>()
        val watch = room.activitiesOf("watch")
        val listen = room.activitiesOf("listen")
        if (watch.isNotEmpty()) tiles += tile(R.drawable.ic_watch, "Watch", countText(watch.size, "source"), "tile-watch") {
            openKind(watch, "watch")
        }
        if (listen.isNotEmpty()) tiles += tile(R.drawable.ic_listen, "Listen", listen.joinToString(", ") { it.name }, "tile-listen") {
            openKind(listen, "listen")
        }
        if (room.lights.isNotEmpty() || room.scenes.isNotEmpty()) {
            val on = room.lights.count { live.isOn(it.entity) }
            tiles += tile(R.drawable.ic_light, "Lights", if (on == 0) "All off" else "$on on", "tile-lights") {
                host.show(LightsScreen(host))
            }
        }
        room.climate?.let { c ->
            val st = live.get(c.entity)
            val sub = when {
                st == null -> c.name
                live.state(c.entity) == "off" -> "Off"
                st.has("t") && !st.isNull("t") -> "${live.state(c.entity).replaceFirstChar { it.uppercase() }} to ${"%.0f".format(st.optDouble("t"))}°"
                else -> live.state(c.entity)
            }
            tiles += tile(R.drawable.ic_comfort, "Comfort", sub, "tile-comfort") { host.show(ComfortScreen(host)) }
        }
        if (room.covers.isNotEmpty()) {
            val open = room.covers.count { live.state(it.entity) == "open" }
            tiles += tile(R.drawable.ic_shades, "Shades", if (open == 0) "Closed" else "$open open", "tile-shades") {
                host.show(ShadesScreen(host))
            }
        }
        if (room.favourites.isNotEmpty()) {
            tiles += tile(R.drawable.ic_star, "Favourites", countText(room.favourites.size, "item"), "tile-favourites") {
                host.show(FavouritesScreen(host))
            }
        }
        parts += grid(tiles, 2, 12, rowHeightDp = tileHeightDp)

        // Room off
        val off = ctx.row(10).apply {
            gravity = Gravity.CENTER
            background = ctx.focusBg(Palette.dangerBg, 16)
            minimumHeight = ctx.dp(64)
            addView(ctx.icon(R.drawable.ic_power, Palette.danger, 22))
            addView(ctx.label("Room Off", 18f, Palette.danger, bold = true))
            tag = "room-off"
            clickable { host.roomOff() }
        }
        parts += off
        return page(*parts.toTypedArray(), scroll = true)
    }

    private var tileHeightDp = 118

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
            background = ctx.focusBg(Palette.raised, 18)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            tag = "now-playing"
            clickable { host.show(ActivityScreen(host, act.id)) }
        }
        val logo = host.logoOf(act)
        val badge = if (logo != null) {
            android.widget.ImageView(ctx).apply {
                setImageBitmap(logo)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = ctx.rounded(Palette.ground, 12)
                clipToOutline = true
                setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
            }
        } else ctx.icon(if (act.kind == "listen") R.drawable.ic_listen else R.drawable.ic_watch, Palette.ground, 26).apply {
            background = ctx.rounded(Palette.accent, 12)
            setPadding(ctx.dp(11), ctx.dp(11), ctx.dp(11), ctx.dp(11))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48))
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

    private fun tile(iconRes: Int, title: String, subtitle: String, tag: String, onClick: () -> Unit): View {
        val col = ctx.column().apply {
            background = ctx.focusBg(Palette.surface, 20)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(14))
            this.tag = tag
            clickable { onClick() }
        }
        // Bigger tiles get a bigger icon and title.
        val big = tileHeightDp >= 170
        col.addView(ctx.icon(iconRes, Palette.accent, if (big) 44 else 30))
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(ctx.label(title, if (big) 22f else 18f, bold = true))
        col.addView(ctx.label(subtitle, if (big) 15f else 13f, Palette.muted).apply {
            maxLines = 1
            setPadding(0, ctx.dp(4), 0, 0)
        })
        return col
    }
}
