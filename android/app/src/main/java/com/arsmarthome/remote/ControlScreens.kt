package com.arsmarthome.remote

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject

/** Pick between several Watch or Listen activities. */
class SourcesScreen(host: Host, private val kind: String) : Screen(host) {
    override val name = "sources"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val active = host.activeActivity()?.id
        val tiles = room.activitiesOf(kind).map { act -> tile(act, act.id == active) }
        val ask = if (kind == "listen") "What do you want to listen to?" else "What do you want to watch?"
        return page(
            header(room.name, if (kind == "listen") "Listen" else "Watch"),
            ctx.label(ask, 15f, Palette.soft),
            grid(tiles, 2, 12),
            scroll = true,
        )
    }

    /** Big logo tile: the activity's logo (or the Watch / Listen icon) and its name. */
    private fun tile(act: Activity, on: Boolean): View {
        val col = ctx.column(10).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ctx.focusBg(if (on) Palette.raised else Palette.surface, 20)
            setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            tag = "act-${act.id}"
            contentDescription = act.name
            clickable { host.startRoomActivity(act) }
        }
        val art = android.widget.ImageView(ctx).apply {
            val bmp = host.logoOf(act)
            if (bmp != null) {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
            } else {
                setImageResource(if (kind == "listen") R.drawable.ic_listen else R.drawable.ic_watch)
                imageTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(ctx.dp(26), ctx.dp(26), ctx.dp(26), ctx.dp(26))
            }
            background = ctx.rounded(Palette.ground, 16)
            clipToOutline = true
        }
        col.addView(art, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(96)))
        val label = ctx.row(6).apply { gravity = Gravity.CENTER }
        label.addView(ctx.label(act.name, 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (on) label.addView(ctx.label("On", 11f, Palette.accent, bold = true, caps = true))
        col.addView(label, matchWrap())
        return col
    }
}

/** Room list. */
class RoomsScreen(host: Host) : Screen(host) {
    override val name = "rooms"
    // The main page: nothing behind it.
    override val parent: (() -> Screen)? = null

    override fun build(): View {
        val project = host.project ?: return page(empty("Not programmed"))
        val rows = project.rooms.map { room ->
            val act = room.activity(host.live.roomActivity[room.id])
            ctx.row(16).apply {
                background = ctx.focusBg(if (room.id == host.room?.id) Palette.raised else Palette.surface, 20)
                setPadding(ctx.dp(20), ctx.dp(24), ctx.dp(20), ctx.dp(24))
                minimumHeight = ctx.dp(84)
                tag = "room-${room.id}"
                addView(ctx.icon(R.drawable.ic_home, if (act != null) Palette.accent else Palette.muted, 28))
                addView(ctx.label(room.name, 22f, bold = true).apply { maxLines = 1 }, weighted())
                addView(ctx.label(act?.name ?: "Off", 16f, if (act != null) Palette.accent else Palette.muted))
                clickable { host.selectRoom(room.id); host.home() }
            }
        }
        return page(header("Choose a room", "Rooms", back = null), *rows.toTypedArray(), scroll = true)
    }
}

/** Scenes + every load. UP/DOWN picks a load, LEFT/RIGHT dims, OK toggles. */
class LightsScreen(host: Host) : Screen(host) {
    override val name = "lights"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val parts = mutableListOf<View>(header(room.name, "Lights"))
        if (room.scenes.isNotEmpty()) {
            parts += ctx.section("Scenes")
            parts += grid(room.scenes.map { s ->
                ctx.keyButton(s.name) { host.send("run", "entity" to s.entity) }.apply { tag = "scene-${s.entity}" }
            }, 3, 8)
        }
        if (room.lights.isNotEmpty()) {
            parts += ctx.section("Lights and switches")
            room.lights.forEach { parts += lightRow(it) }
            parts += ctx.label("Tap to switch · drag the bar to dim · Left / Right dims", 12f, Palette.muted).apply {
                gravity = Gravity.CENTER
            }
        }
        return page(*parts.toTypedArray(), scroll = true)
    }

    private fun level(light: LightItem): Int {
        val st = host.live.get(light.entity) ?: return 0
        if (st.optString("s") != "on") return 0
        return st.optInt("b", 100)
    }

    private fun lightRow(light: LightItem): View {
        val lvl = level(light)
        val col = ctx.column(10).apply {
            background = ctx.focusBg(Palette.surface, 16)
            setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            tag = "light:${light.entity}"
            clickable { toggle(light) }
        }
        val top = ctx.row()
        top.addView(ctx.label(light.name, 17f, bold = true).apply { maxLines = 1 }, weighted())
        val status = ctx.label(if (lvl > 0) (if (light.dimmable) "$lvl%" else "On") else "Off", 15f, if (lvl > 0) Palette.accent else Palette.soft)
        top.addView(status)
        col.addView(top, matchWrap())
        if (light.dimmable) {
            // Drag with a finger; tap the rest of the row to switch on / off.
            col.addView(DimmerView(ctx, lvl) { level, final ->
                status.text = if (level > 0) "$level%" else "Off"
                if (final) {
                    optimistic(light.entity, level)
                }
                host.send("light", "entity" to light.entity, "action" to "set", "brightness" to level)
            }, matchWrap())
        }
        return col
    }

    private fun toggle(light: LightItem) {
        val on = level(light) > 0
        optimistic(light.entity, if (on) 0 else 100)
        host.send("light", "entity" to light.entity, "action" to "toggle")
    }

    private fun dim(light: LightItem, delta: Int) {
        val target = (level(light) + delta).coerceIn(0, 100)
        optimistic(light.entity, target)
        host.send("light", "entity" to light.entity, "action" to "set", "brightness" to target)
    }

    private fun optimistic(entity: String, level: Int) {
        host.live.entities[entity] = JSONObject().put("s", if (level > 0) "on" else "off").put("b", level)
        host.refreshNow()
    }

    override fun onKey(key: Key, event: KeyEvent): Boolean {
        if (key != Key.LEFT && key != Key.RIGHT) return false
        val tag = (ctx as? android.app.Activity)?.currentFocus?.tag as? String ?: return false
        if (!tag.startsWith("light:")) return false
        val light = host.room?.lights?.firstOrNull { it.entity == tag.removePrefix("light:") } ?: return false
        if (!light.dimmable) return true
        dim(light, if (key == Key.RIGHT) 10 else -10)
        return true
    }
}

/** One climate entity: big temperature, setpoint, modes. */
class ComfortScreen(host: Host) : Screen(host) {
    override val name = "comfort"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val climate = room.climate ?: return page(header(room.name, "Comfort"), empty("No climate in this room"))
        val st = host.live.get(climate.entity) ?: JSONObject()
        val mode = st.optString("s", "off")

        val big = ctx.column(4).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ctx.rounded(Palette.surface, 20)
            setPadding(ctx.dp(16), ctx.dp(22), ctx.dp(16), ctx.dp(22))
        }
        big.addView(ctx.label("Inside", 13f, Palette.muted))
        big.addView(ctx.label(if (st.has("c") && !st.isNull("c")) "${"%.1f".format(st.optDouble("c"))}°" else "—", 44f, bold = true))
        big.addView(ctx.label(climate.name, 14f, Palette.muted))

        val setRow = ctx.row(14).apply { gravity = Gravity.CENTER }
        setRow.addView(ctx.keyButton("–") { host.send("climate", "entity" to climate.entity, "action" to "temp_down") }.apply {
            tag = "temp-down"; textSize = 26f
        }, weighted(1f, ctx.dp(64)))
        val target = ctx.column(2).apply { gravity = Gravity.CENTER }
        target.addView(ctx.label("Set to", 12f, Palette.muted, caps = true))
        target.addView(ctx.label(if (st.has("t") && !st.isNull("t")) "${"%.0f".format(st.optDouble("t"))}°" else "—", 30f, Palette.accent, bold = true))
        setRow.addView(target, weighted(1.2f))
        setRow.addView(ctx.keyButton("+") { host.send("climate", "entity" to climate.entity, "action" to "temp_up") }.apply {
            tag = "temp-up"; textSize = 26f
        }, weighted(1f, ctx.dp(64)))

        val modes = st.optJSONArray("modes")?.strings()?.takeIf { it.isNotEmpty() } ?: listOf("off", "cool", "heat", "auto")
        val chips = modes.map { m ->
            ctx.pill(m.replace('_', ' ').replaceFirstChar { it.uppercase() }, m == mode) {
                host.send("climate", "entity" to climate.entity, "action" to "mode", "value" to m)
            }.apply { tag = "mode-$m" }
        }
        return page(header(room.name, "Comfort"), big, setRow, ctx.section("Mode"), grid(chips, 3, 8), scroll = true)
    }
}

/** Shades with open / stop / close. */
class ShadesScreen(host: Host) : Screen(host) {
    override val name = "shades"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val rows = room.covers.map { cover ->
            val st = host.live.get(cover.entity)
            val col = ctx.column(10).apply {
                background = ctx.rounded(Palette.surface, 16)
                setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
            }
            val top = ctx.row()
            top.addView(ctx.label(cover.name, 17f, bold = true).apply { maxLines = 1 }, weighted())
            val pos = st?.optInt("p", -1) ?: -1
            top.addView(ctx.label(if (pos >= 0) "$pos%" else (st?.optString("s") ?: "—").replaceFirstChar { it.uppercase() }, 15f, Palette.soft))
            col.addView(top, matchWrap())
            val buttons = listOf("open" to "Open", "stop" to "Stop", "close" to "Close").map { (action, text) ->
                ctx.keyButton(text) { host.send("cover", "entity" to cover.entity, "action" to action) }.apply {
                    tag = "cover-$action-${cover.entity}"
                    minHeight = ctx.dp(44)
                }
            }
            col.addView(grid(buttons, 3, 8), matchWrap())
            col
        }
        return page(header(room.name, "Shades"), *rows.toTypedArray(), scroll = true)
    }
}

/** Favourites: one-press scenes and scripts. */
class FavouritesScreen(host: Host) : Screen(host) {
    override val name = "favourites"
    override val parent: (() -> Screen) = { HomeScreen(host) }

    override fun build(): View {
        val room = host.room ?: return page(empty("No room"))
        val buttons = room.favourites.map { f ->
            ctx.column(10).apply {
                background = ctx.focusBg(Palette.surface, 18)
                setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
                tag = "fav-${f.entity}"
                addView(ctx.icon(R.drawable.ic_star, Palette.accent, 24))
                addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
                addView(ctx.label(f.name, 16f, bold = true).apply { maxLines = 2 })
                clickable { host.send("run", "entity" to f.entity) }
            }
        }
        return page(header(room.name, "Favourites"), grid(buttons, 2, 10, rowHeightDp = 100), scroll = true)
    }
}

/** Shown until Home Assistant links the remote. */
class SetupScreen(host: Host, private val address: String) : Screen(host) {
    override val name = "setup"

    override fun build(): View {
        val col = ctx.column(16).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
        }
        val logo = Logo.bitmap(ctx)
        if (logo != null) {
            col.addView(android.widget.ImageView(ctx).apply {
                setImageBitmap(logo)
                adjustViewBounds = true
                maxHeight = ctx.dp(96)
                maxWidth = ctx.dp(220)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        } else {
            col.addView(ctx.icon(R.drawable.ic_launcher, Palette.accent, 64).apply { imageTintList = null })
        }
        col.addView(ctx.label("AR Remote", 28f, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(ctx.label("Waiting for Home Assistant", 16f, Palette.soft).apply { gravity = Gravity.CENTER })
        col.addView(ctx.label(address, 22f, Palette.accent, bold = true).apply { gravity = Gravity.CENTER })
        col.addView(
            ctx.label(
                "In Home Assistant add the AR Remote integration with this address, then open Configure to build your rooms.",
                14f, Palette.muted,
            ).apply { gravity = Gravity.CENTER },
        )
        return col
    }
}
