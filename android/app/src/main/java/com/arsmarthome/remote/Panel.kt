package com.arsmarthome.remote

import android.Manifest
import android.content.pm.PackageManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The pull-down settings panel (swipe down from the top edge, or long-press
 * Home) and its pages: Wi-Fi, installer PIN and installer settings.
 */

/** Shared building blocks for the panel pages. */
abstract class PanelScreen(host: Host, private val back: Screen?) : Screen(host) {
    override val holdsInput = true
    override val parent: (() -> Screen) = { back ?: HomeScreen(host) }
    protected val app: MainActivity get() = host as MainActivity

    protected fun close() { host.show(back ?: HomeScreen(host)) }

    protected fun row(iconRes: Int, title: String, subtitle: String?, tint: Int = Palette.muted, chevron: Boolean = true, tag: String? = null, onClick: (() -> Unit)?): View {
        val r = ctx.row(12).apply {
            background = ctx.focusBg(Palette.raised, 16)
            setPadding(ctx.dp(14), ctx.dp(13), ctx.dp(14), ctx.dp(13))
            minimumHeight = ctx.dp(58)
            if (tag != null) this.tag = tag
            if (onClick != null) clickable { onClick() }
        }
        r.addView(ctx.icon(iconRes, tint, 22))
        val text = ctx.column(3)
        text.addView(ctx.label(title, 16f, bold = true).apply { maxLines = 1 })
        if (!subtitle.isNullOrEmpty()) text.addView(ctx.label(subtitle, 12f, Palette.muted).apply { maxLines = 2 })
        r.addView(text, weighted())
        if (chevron && onClick != null) r.addView(ctx.label("›", 22f, Palette.muted))
        return r
    }

    protected fun note(text: String): View = ctx.label(text, 12f, Palette.muted).apply {
        setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
    }
}

// --------------------------------------------------------------------- panel

class SettingsScreen(host: Host, back: Screen?) : PanelScreen(host, back) {
    override val name = "settings"

    override fun build(): View {
        val a = app
        val parts = mutableListOf<View>()

        // Title + time / battery
        val top = ctx.row(10)
        top.addView(ctx.label("Settings", 24f, bold = true), weighted())
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val batt = if (a.batteryLevel >= 0) " · ${a.batteryLevel}%${if (a.isCharging) " ⚡" else ""}" else ""
        top.addView(ctx.label("$time$batt", 14f, Palette.muted))
        top.addView(ctx.icon(R.drawable.ic_chevron_up, Palette.text, 22).apply {
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            background = ctx.focusBg(Palette.surface, 12)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44))
            contentDescription = "Close"
            tag = "panel-close"
            clickable { close() }
        })
        parts += top

        // Brightness
        parts += ctx.section("Brightness")
        parts += DimmerView(ctx, a.store.brightness) { level, final -> a.setBrightness(level, final) }

        // Quick toggles
        parts += ctx.section("Quick")
        val toggles = listOf(
            Triple("clock", R.drawable.ic_clock, "Logo clock"),
            Triple("pickup", R.drawable.ic_phone, "Wake on lift"),
            Triple("charging", R.drawable.ic_bolt, "Charge screen"),
            Triple("night", R.drawable.ic_moon, "Night dim"),
            Triple("sounds", R.drawable.ic_volume, "Key sounds"),
            Triple("vibrate", R.drawable.ic_vibrate, "Vibrate"),
        ).map { (key, icon, label) -> toggle(key, icon, label) }
        parts += grid(toggles, 3, 8)

        // Screen timeout
        parts += ctx.section("Screen off after")
        val current = a.screenTimeoutSeconds()
        val choices = listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")
        parts += grid(choices.map { (sec, text) ->
            ctx.pill(text, current == sec) {
                if (!a.setScreenTimeout(sec)) a.toast("Run tools/fix-remote once to allow this")
                host.refreshNow()
            }.apply { tag = "timeout-$sec" }
        }, 5, 6)
        if (!a.canChangeTimeout()) parts += note("To change this, run tools/fix-remote once over USB.")

        // Connection
        parts += ctx.section("Connection")
        val ssid = a.wifi.currentSsid()
        parts += row(
            R.drawable.ic_wifi, "Wi-Fi",
            if (ssid != null) "$ssid · ${a.ipAddress()}" else if (a.wifi.isOn()) "Not connected" else "Off",
            if (ssid != null) Palette.accent else Palette.muted, tag = "wifi",
        ) { host.show(WifiScreen(host, this)) }
        val ok = a.haConnected()
        parts += row(
            R.drawable.ic_home, "Home Assistant",
            if (ok) "Connected" else "Not connected — check Wi-Fi",
            if (ok) Palette.green else Palette.danger, onClick = null,
        )

        // About
        parts += ctx.section("About")
        parts += ctx.label("AR Remote ${a.versionName()} · Android ${android.os.Build.VERSION.RELEASE}\nby A&R Smart Home Automation", 13f, Palette.soft).apply {
            setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
        }
        parts += row(R.drawable.ic_lock, "Installer settings", if (a.pinHash() != null) "PIN protected" else "No PIN set (set one in Home Assistant)", tag = "installer") {
            if (a.pinHash() == null) host.show(InstallerScreen(host, this))
            else host.show(PinScreen(host, this) { host.show(InstallerScreen(host, this)) })
        }
        parts += ctx.label("Swipe up from the bottom, press Back or ⌃ to close", 12f, Palette.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(4), 0, ctx.dp(8))
        }
        return page(*parts.toTypedArray(), scroll = true)
    }

    private fun toggle(key: String, iconRes: Int, label: String): View {
        val on = app.quick(key)
        val col = ctx.column(8).apply {
            background = ctx.focusBg(if (on) Palette.raised else Palette.surface, 16)
            setPadding(ctx.dp(10), ctx.dp(12), ctx.dp(10), ctx.dp(12))
            tag = "q-$key"
            contentDescription = "$label ${if (on) "on" else "off"}"
            clickable {
                app.setQuick(key, !on)
                host.refreshNow()
            }
        }
        col.addView(ctx.icon(iconRes, if (on) Palette.accent else Palette.muted, 24))
        col.addView(ctx.label(label, 12f, if (on) Palette.text else Palette.muted, bold = on).apply { maxLines = 1 })
        return col
    }
}

// ---------------------------------------------------------------------- Wi-Fi

class WifiScreen(host: Host, back: Screen?) : PanelScreen(host, back) {
    override val name = "wifi"

    private var networks: List<WifiNet>? = null
    private var scanning = false
    private var selected: WifiNet? = null
    private var password = ""
    private var status: String? = null
    private var askedPermission = false

    override fun build(): View {
        val a = app
        val parts = mutableListOf<View>(header("Settings", "Wi-Fi") { close() })

        if (!a.hasLocationPermission()) {
            if (!askedPermission) { askedPermission = true; main.post { a.askLocationPermission() } }
            parts += note("Android needs the Location permission to list Wi-Fi networks. Tap Allow when it asks.")
        }
        if (!a.wifi.isOn()) {
            parts += ctx.keyButton("Turn Wi-Fi on", active = true) { a.wifi.turnOn(); host.refreshNow() }.apply { tag = "wifi-on" }
        }
        val current = a.wifi.currentSsid()
        parts += ctx.label(
            status ?: if (current != null) "Connected to $current · ${a.ipAddress()}" else "Not connected",
            14f, if (current != null) Palette.accent else Palette.soft,
        )
        parts += ctx.keyButton(if (scanning) "Searching…" else "Search for networks") { scan() }.apply { tag = "wifi-scan" }

        val list = networks
        if (list == null && !scanning) scan()
        list?.forEach { net ->
            val isCurrent = net.ssid == current
            parts += row(
                R.drawable.ic_wifi, net.ssid,
                when { isCurrent -> "Connected"; net.secure -> "Secured"; else -> "Open" },
                if (isCurrent) Palette.accent else when (net.level) { 3 -> Palette.text; 2 -> Palette.soft; else -> Palette.muted },
                chevron = false, tag = "net-${net.ssid}",
            ) {
                if (isCurrent) return@row
                selected = net
                password = ""
                if (!net.secure) connect() else host.refreshNow()
            }
            if (selected?.ssid == net.ssid && net.secure) parts += passwordBox(net)
        }
        if (list != null && list.isEmpty()) {
            parts += note("No networks found. Make sure Wi-Fi is on; on some remotes Location must be on too (tools/fix-remote switches it on).")
        }
        parts += row(R.drawable.ic_wifi, "Android Wi-Fi settings", "Hidden networks, forget a network, advanced", onClick = { a.wifi.openSystemWifi() })
        if (!a.wifi.canJoinDirectly) {
            parts += note("On this Android version, Android asks you to confirm the network after you tap Connect.")
        }
        return page(*parts.toTypedArray(), scroll = true)
    }

    private fun passwordBox(net: WifiNet): View {
        val box = ctx.column(10).apply {
            background = ctx.rounded(Palette.surface, 16)
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        }
        box.addView(ctx.label("Password for ${net.ssid}", 13f, Palette.soft))
        val field = EditText(ctx).apply {
            setText(password)
            setSelection(password.length)
            setTextColor(Palette.text)
            setHintTextColor(Palette.muted)
            hint = "Wi-Fi password"
            textSize = 17f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_GO
            background = ctx.rounded(Palette.ground, 12, Palette.accent, 2)
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            tag = "wifi-password"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { password = s?.toString() ?: "" }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO) { connect(); true } else false
            }
        }
        box.addView(field, matchWrap())
        field.post { field.requestFocus() }
        box.addView(ctx.keyButton("Connect", active = true) { connect() }.apply { tag = "wifi-connect" })
        return box
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Never redraws in the middle of build(): everything is posted. */
    private fun scan() {
        if (scanning) return
        scanning = true
        main.post {
            app.wifi.scan { result ->
                main.post {
                    scanning = false
                    networks = result
                    host.refreshNow()
                }
            }
            host.refreshNow()
        }
    }

    private fun connect() {
        val net = selected ?: return
        val a = app
        a.hideKeyboard()
        val direct = a.wifi.join(net, password)
        selected = null
        password = ""
        status = if (direct) "Connecting to ${net.ssid}…" else "Finish joining ${net.ssid} on Android's screen"
        host.refreshNow()
        if (direct) a.watchWifi(net.ssid) { ok ->
            status = if (ok) null else "Couldn't join ${net.ssid} — check the password"
            host.refreshNow()
        }
    }

    override fun onKey(key: Key, event: KeyEvent): Boolean = false
}

// ------------------------------------------------------------------ PIN pad

class PinScreen(host: Host, back: Screen?, private val onOk: () -> Unit) : PanelScreen(host, back) {
    override val name = "pin"
    private var entered = ""
    private var wrong = false

    override fun build(): View {
        val parts = mutableListOf<View>(header("Settings", "Installer PIN") { close() })
        parts += ctx.label(if (wrong) "Wrong PIN — try again" else "Enter the installer PIN", 15f, if (wrong) Palette.danger else Palette.soft).apply {
            gravity = Gravity.CENTER
        }
        parts += ctx.label(if (entered.isEmpty()) "–  –  –  –" else "●  ".repeat(entered.length).trim(), 30f, Palette.text, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK").map { k ->
            ctx.keyButton(k, active = k == "OK") { press(k) }.apply {
                tag = "pin-$k"
                textSize = 24f
                minHeight = ctx.dp(64)
            }
        }
        parts += grid(keys, 3, 10)
        parts += note("The PIN is set in Home Assistant → AR Remote → Configure → Remote settings.")
        return page(*parts.toTypedArray(), scroll = true)
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> entered = entered.dropLast(1)
            "OK" -> { check(); return }
            else -> if (entered.length < 8) entered += k
        }
        wrong = false
        host.refreshNow()
    }

    private fun check() {
        if (pinHash(entered) == app.pinHash()) {
            onOk()
        } else {
            wrong = true
            entered = ""
            host.refreshNow()
        }
    }

    override fun onKey(key: Key, event: KeyEvent): Boolean = false

    companion object {
        /** Same as the integration: sha256("ar_remote:" + pin). */
        fun pinHash(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("ar_remote:$pin".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

// --------------------------------------------------------- installer settings

class InstallerScreen(host: Host, back: Screen?) : PanelScreen(host, back) {
    override val name = "installer"

    override fun build(): View {
        val a = app
        val parts = mutableListOf<View>(header("Settings", "Installer settings") { close() })
        parts += row(R.drawable.ic_comfort, "Android settings", "The remote's full Android settings", tag = "inst-android") { a.openAndroidSettings() }
        parts += row(R.drawable.ic_wifi, "Wi-Fi", a.wifi.currentSsid() ?: "Not connected", tag = "inst-wifi") { host.show(WifiScreen(host, this)) }
        parts += row(R.drawable.ic_power, "Restart AR Remote", "Closes and reopens the app", tag = "inst-restart") { a.restartApp() }
        parts += row(R.drawable.ic_down, "Check for app update", "Downloads the latest release and asks to install", tag = "inst-update") { a.checkForUpdate() }
        parts += row(
            R.drawable.ic_home, "Pairing address",
            "${a.ipAddress()}:${MainActivity.PORT}  ·  enter this when adding the remote in Home Assistant\n" +
                if (a.haConnected()) "Linked to Home Assistant" else "Not linked to Home Assistant yet",
            onClick = null,
        )
        parts += note("Lock screen, screensaver and the stock launcher are handled by tools/fix-remote over USB.")
        return page(*parts.toTypedArray(), scroll = true)
    }
}

/** Location permission helpers used by the Wi-Fi page. */
internal fun MainActivity.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
