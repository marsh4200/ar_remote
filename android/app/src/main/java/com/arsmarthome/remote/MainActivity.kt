package com.arsmarthome.remote

import android.annotation.SuppressLint
import android.app.Activity as AndroidActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AndroidActivity(), Host, ConfigServer.Routes {

    lateinit var store: Store
        private set
    lateinit var wifi: Wifi
        private set
    private lateinit var link: HaLink
    private lateinit var server: ConfigServer
    private lateinit var updater: Updater
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var waker: Waker
    private var overlay: View? = null
    private var overlayKey: Key? = null
    private val main = Handler(Looper.getMainLooper())

    override val ctx: Context get() = this
    override var project: Project? = null
        private set
    override val live = LiveState()
    private var roomId: String? = null
    private var screen: Screen? = null

    private var battery = -1
    private var charging = false
    val batteryLevel: Int get() = battery
    val isCharging: Boolean get() = charging

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        root = FrameLayout(this).apply { setBackgroundColor(Palette.ground) }
        stage = FrameLayout(this)
        root.addView(stage, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        store = Store(this)
        wifi = Wifi(this)
        @SuppressLint("HardwareIds")
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "astrion"
        link = HaLink(store, androidId)
        project = store.loadProject()
        roomId = store.roomId

        server = ConfigServer(PORT, this)
        server.start()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        waker = Waker(this) { Waker.wakeScreen(this) }
        applyDeviceSettings()
        waker.start()
        askDozeExemption()
        lastInput = SystemClock.uptimeMillis()
        main.postDelayed(idleWatch, 5000)
        updater = Updater(this) { status ->
            main.post {
                Toast.makeText(this, status, Toast.LENGTH_LONG).show()
                send("update", "status" to status)
            }
        }
        updater.register()
        showStart()
        showSplash()
    }

    override fun onResume() {
        super.onResume()
        applyBrightness()
        dismissLockScreen()
        hello()
    }

    /** Skip Android's swipe-to-unlock when there's no PIN (we are the remote's UI). */
    private fun dismissLockScreen() {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (km.isKeyguardLocked && !km.isKeyguardSecure) {
            try { km.requestDismissKeyguard(this, null) } catch (_: Exception) {}
        }
    }

    private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** Which app Android opens for Home (should be us). */
    private fun defaultHome(): String = try {
        packageManager.resolveActivity(homeIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: ""
    } catch (e: Exception) { "" }

    /** Other launchers that are switched on (e.g. RosCard came back). */
    private fun otherLaunchers(): List<String> = try {
        packageManager.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName && it != "com.android.settings" && it != "android" }
            .distinct()
    } catch (e: Exception) { emptyList() }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // HOME pressed while we are the launcher.
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) mainPage()
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(powerReceiver) } catch (_: Exception) {}
        waker.stop()
        main.removeCallbacks(idleWatch)
        updater.unregister()
        server.stop()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Never leave the launcher.
    }

    private fun showStart() {
        if (store.webhookUrl == null || project == null) {
            show(SetupScreen(this, "${ipAddress()}:$PORT"))
        } else {
            mainPage()
        }
    }

    private fun hello() {
        if (store.webhookUrl == null) return
        val msg = JSONObject()
            .put("type", "hello")
            .put("version", appVersion().first)
            .put("project_version", project?.version ?: "")
        link.send(msg) { reply ->
            reply.optJSONObject("project")?.let { applyProject(it) }
            reply.optJSONObject("state")?.let { live.apply(it); refresh() }
        }
    }

    // ------------------------------------------------------------------ host

    override val room: Room?
        get() {
            val p = project ?: return null
            return p.room(roomId) ?: p.room(p.defaultRoom) ?: p.rooms.firstOrNull()
        }

    override fun activeActivity(): Activity? {
        val r = room ?: return null
        return r.activity(live.roomActivity[r.id])
    }

    override fun selectRoom(id: String) {
        roomId = id
        store.roomId = id
    }

    override fun show(screen: Screen) {
        this.screen = screen
        render(keepFocus = false)
        send("screen", "screen" to screen.name)
    }

    override fun home() {
        if (store.webhookUrl == null || project == null) showStart() else show(HomeScreen(this))
    }

    override fun mainPage() {
        val p = project
        when {
            store.webhookUrl == null || p == null -> showStart()
            p.rooms.size > 1 -> show(RoomsScreen(this))
            else -> show(HomeScreen(this))
        }
    }

    override fun send(type: String, vararg fields: Pair<String, Any?>) {
        val msg = JSONObject().put("type", type)
        val r = room
        if (r != null) msg.put("room", r.id)
        fields.forEach { (k, v) -> if (v != null) msg.put(k, v) }
        link.send(msg)
    }

    override fun startRoomActivity(activity: Activity) {
        val r = room ?: return
        live.roomActivity[r.id] = activity.id // optimistic; HA confirms via state push
        send("activity", "activity" to activity.id)
        show(ActivityScreen(this, activity.id))
    }

    override fun logoOf(activity: Activity): android.graphics.Bitmap? =
        Icons.get(this, store, activity.logo) { refresh() }

    override fun roomOff() {
        val r = room ?: return
        live.roomActivity[r.id] = null
        send("room_off")
        // Back to the room list, not the room's Listen / Watch page.
        mainPage()
    }

    private var refreshQueued = false

    /** Last touch or key press: state pushes don't rebuild the screen under your finger. */
    private var lastInput = 0L

    private var edgeStartY = -1f
    private var edgeFromTop = false
    private var swallowGesture = false

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swallowGesture = false
                val h = root.height.toFloat()
                edgeStartY = -1f
                if (overlay == null && ev.y < dp(28)) { edgeStartY = ev.y; edgeFromTop = true }
                else if (overlay == null && screen is PanelScreen && h > 0 && ev.y > h - dp(36)) { edgeStartY = ev.y; edgeFromTop = false }
            }
            android.view.MotionEvent.ACTION_MOVE -> if (edgeStartY >= 0 && !swallowGesture) {
                val dy = ev.y - edgeStartY
                if ((edgeFromTop && dy > dp(70)) || (!edgeFromTop && dy < -dp(70))) {
                    swallowGesture = true
                    // Cancel whatever the finger was pressing, then open / close the panel.
                    val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    if (edgeFromTop) openSettings() else closeSettings()
                }
            }
        }
        if (swallowGesture) {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP || ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
                edgeStartY = -1f
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // --------------------------------------------------------- settings panel

    /** Pull the settings panel down over whatever is on screen. */
    fun openSettings() {
        if (screen is PanelScreen) return
        show(SettingsScreen(this, screen))
        stage.getChildAt(0)?.let { v ->
            v.translationY = -root.height.toFloat()
            v.animate().translationY(0f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    fun closeSettings() {
        val s = screen as? PanelScreen ?: return
        val target = s.parent.invoke()
        val v = stage.getChildAt(0)
        if (v == null) { show(target); return }
        v.animate().translationY(-root.height.toFloat()).setDuration(240)
            .withEndAction { show(target) }.start()
    }

    fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    fun versionName(): String = appVersion().first

    fun haConnected(): Boolean = store.webhookUrl != null && link.lastOk

    fun pinHash(): String? = project?.device?.pinHash

    /** The remote's own quick settings (clock / pickup / charging follow HA until changed here). */
    fun quick(key: String): Boolean = when (key) {
        "clock" -> settings.clock != "off"
        "pickup" -> settings.pickup != "off"
        "charging" -> settings.chargingScreen
        "night" -> store.flag("night", false)
        "sounds" -> store.flag("sounds", false)
        "vibrate" -> store.flag("vibrate", false)
        else -> false
    }

    fun setQuick(key: String, on: Boolean) {
        when (key) {
            "clock", "pickup", "charging" -> { store.setOverride(key, on); applyDeviceSettings() }
            else -> store.setFlag(key, on)
        }
        if (key == "clock" && !on && overlay is ClockView) hideOverlay()
        applyBrightness()
    }

    fun setBrightness(level: Int, final: Boolean) {
        store.brightness = level
        applyBrightness()
    }

    /** Brightness slider, dimmed further at night (22:00–06:00) and under the clock. */
    private fun applyBrightness() {
        var b = store.brightness / 100f
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (store.flag("night", false) && (hour >= 22 || hour < 6)) b *= 0.4f
        if (overlay is ClockView) b = minOf(b, 0.25f)
        b = b.coerceIn(0.02f, 1f)
        if (window.attributes.screenBrightness == b) return
        window.attributes = window.attributes.apply { screenBrightness = b }
    }

    /** Click and / or buzz on each key press (Quick settings). */
    private fun keyFeedback() {
        if (store.flag("sounds", false)) {
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            audio.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f)
        }
        if (store.flag("vibrate", false)) {
            val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            try {
                vib?.vibrate(android.os.VibrationEffect.createOneShot(25, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }
    }

    fun canChangeTimeout(): Boolean = Settings.System.canWrite(this)

    fun screenTimeoutSeconds(): Int =
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 30_000) / 1000

    fun setScreenTimeout(seconds: Int): Boolean {
        if (!canChangeTimeout()) return false
        return try {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1000)
        } catch (e: Exception) { false }
    }

    fun askLocationPermission() {
        requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) refreshNow()
    }

    fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    /** After joining a network: report back once it's connected (or after ~25 s). */
    fun watchWifi(ssid: String, done: (Boolean) -> Unit) {
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                if (wifi.currentSsid() == ssid) { done(true); hello(); return }
                if (++tries >= 12) { done(false); return }
                main.postDelayed(this, 2000)
            }
        }
        main.postDelayed(check, 2000)
    }

    fun openAndroidSettings() {
        try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("Android settings aren't available") }
    }

    fun restartApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(
            this, 1, intent, android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarm.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, pending)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun checkForUpdate() {
        try { updater.install(APK_URL) } catch (e: Exception) { toast(e.message ?: "Update already running") }
    }

    private var seenScrollY = -1

    /** Redraw for a state push from HA: waits until you've stopped scrolling / pressing. */
    override fun refresh() {
        if (screen?.holdsInput == true) return
        if (refreshQueued) return
        refreshQueued = true
        main.postDelayed({ flushRefresh() }, 60)
    }

    /** Redraw right away (your own change, e.g. dimming a light). */
    override fun refreshNow() {
        render(keepFocus = true)
    }

    private fun flushRefresh() {
        val idle = SystemClock.uptimeMillis() - lastInput
        val y = (stage.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: -1
        val flinging = y != seenScrollY // still moving since the last check
        seenScrollY = y
        if (idle < 500 || (flinging && idle < 3000)) {
            main.postDelayed({ flushRefresh() }, 150)
            return
        }
        refreshQueued = false
        if (screen?.holdsInput == true) return
        render(keepFocus = true)
    }

    private fun render(keepFocus: Boolean) {
        val s = screen ?: return
        val focusedTag = if (keepFocus) currentFocus?.tag else null
        val scrollY = if (keepFocus) firstScrollY() else 0
        val view = try {
            s.build()
        } catch (e: Exception) {
            Log.e(HaLink.TAG, "render ${s.name} failed", e)
            return
        }
        stage.removeAllViews()
        stage.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val target = focusedTag?.let { view.findViewWithTag<View>(it) }
        if (target != null) target.requestFocus()
        else if (!keepFocus && !s.routesToDevice) view.post { view.requestFocus(View.FOCUS_DOWN) }
        val scroller = view as? android.widget.ScrollView
        if (scrollY > 0 && scroller != null) {
            // Restore the scroll after layout but before the first frame is drawn, so
            // the list never flashes at the top and jumps back.
            scroller.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    scroller.viewTreeObserver.removeOnPreDrawListener(this)
                    scroller.scrollTo(0, scrollY)
                    return true
                }
            })
        }
    }

    private fun firstScrollY(): Int = (stage.getChildAt(0) as? android.widget.ScrollView)?.scrollY ?: 0

    // -------------------------------------------------------------- overlays

    /** HA's remote settings, with anything changed on the remote's own panel on top. */
    private val settings: DeviceSettings
        get() {
            val base = project?.device ?: DeviceSettings()
            return base.copy(
                clock = store.override("clock")?.let { on -> if (!on) "off" else if (base.clock == "off") "charging" else base.clock } ?: base.clock,
                pickup = store.override("pickup")?.let { on -> if (!on) "off" else if (base.pickup == "off") "auto" else base.pickup } ?: base.pickup,
                chargingScreen = store.override("charging") ?: base.chargingScreen,
            )
        }

    /**
     * Android's deep sleep ("Doze") ignores the wake lock pick-up detection needs.
     * Ask once to be exempt (one tap on the remote). install-usb and the HA ADB
     * installer grant it silently, so usually this never shows.
     */
    private fun askDozeExemption() {
        if (settings.pickup == "off" || store.askedDoze) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        store.askedDoze = true
        try {
            @SuppressLint("BatteryLife")
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
            startActivity(ask)
        } catch (e: Exception) {
            Log.w(HaLink.TAG, "doze exemption: ${e.message}")
        }
    }

    private fun applyDeviceSettings() {
        val s = settings
        waker.configure(s.pickup, s.pickupSensitivity)
        updateKeepOn()
        Logo.sync(this, store, s.logo) { if (screen is SetupScreen) refresh() }
    }

    private fun showOverlay(view: View) {
        hideOverlay()
        overlay = view
        view.setOnClickListener { hideOverlay() }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun hideOverlay(fade: Boolean = true) {
        val v = overlay ?: return
        overlay = null
        lastInput = SystemClock.uptimeMillis() // the clock waits a full idle period again
        if (v is ClockView) applyBrightness()
        if (!fade) { root.removeView(v); return }
        v.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(v) }.start()
    }

    private var splashPending: SplashView? = null

    /**
     * Your logo when the app starts. After a reboot Android starts us while its own
     * boot animation still covers the screen, so the logo's time only starts once
     * boot has finished and our window is actually visible.
     */
    private fun showSplash() {
        if (settings.splashSeconds <= 0) return
        val splash = SplashView(this, Logo.bitmap(this))
        showOverlay(splash)
        splashPending = splash
        startSplashWhenVisible()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) startSplashWhenVisible()
    }

    private fun startSplashWhenVisible(tries: Int = 0) {
        val splash = splashPending ?: return
        if (overlay !== splash) { splashPending = null; return } // dismissed by a key
        if (!hasWindowFocus()) return // onWindowFocusChanged calls back
        Thread {
            val booted = bootCompleted()
            main.post {
                if (splashPending !== splash) return@post
                if (!booted && tries < 240) {
                    main.postDelayed({ startSplashWhenVisible(tries + 1) }, 500)
                    return@post
                }
                splashPending = null
                // Give the boot animation a moment to fade, then play the logo in.
                main.postDelayed({
                    if (overlay !== splash) return@postDelayed
                    splash.replay()
                    main.postDelayed({ if (overlay === splash) hideOverlay() }, settings.splashSeconds * 1000L + 700)
                }, if (tries > 0) 600L else 0L)
            }
        }.start()
    }

    /** True once Android has finished booting (its boot animation is gone). */
    private fun bootCompleted(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "sys.boot_completed"))
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        out == "1"
    } catch (e: Exception) {
        true
    }

    // ------------------------------------------------------------ clock

    /** Clock screensaver wanted right now? (on the charger, or always) */
    private fun clockWanted(): Boolean = when (settings.clock) {
        "always" -> true
        "charging" -> charging
        else -> false
    }

    /** On the charger the screen stays on (the clock takes over instead of going dark). */
    private fun updateKeepOn() {
        if (clockWanted()) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private val idleWatch = object : Runnable {
        override fun run() {
            val idle = SystemClock.uptimeMillis() - lastInput
            val o = overlay
            if (clockWanted() && idle >= settings.clockAfter * 1000L && o !is ClockView && o !is SplashView &&
                screen !is SetupScreen
            ) {
                showClock()
            }
            applyBrightness() // night dim starts / ends on its own
            main.postDelayed(this, 5000)
        }
    }

    private fun showClock() {
        val custom = Logo.isCustom(this)
        val view = ClockView(this, Logo.bitmap(this), if (custom) ClockView.CENTRE_DIAL else ClockView.BUILT_IN_DIAL)
        view.alpha = 0f
        showOverlay(view)
        view.animate().alpha(1f).setDuration(600).start()
        applyBrightness() // dims while the clock is up
    }

    private fun showCharging() {
        if (!settings.chargingScreen) return
        (overlay as? ChargingView)?.let { it.update(battery, true); return }
        val view = ChargingView(this, Logo.bitmap(this))
        view.alpha = 0f
        showOverlay(view)
        view.update(if (battery >= 0) battery else 0, true)
        view.animate().alpha(1f).setDuration(300).start()
    }

    private fun applyProject(json: JSONObject) {
        store.saveProject(json)
        project = Project.parse(json)
        if (project?.room(roomId) == null) roomId = project?.defaultRoom
        project?.let { p -> Icons.prune(this, p.rooms.flatMap { r -> r.activities.mapNotNull { it.logo } }) }
        applyDeviceSettings()
        if (screen == null || screen is SetupScreen) mainPage() else refresh()
    }

    // ------------------------------------------------------------------ keys

    private val downAt = HashMap<Key, Long>()
    private val consumed = HashSet<Key>()
    private var lastRepeat = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) keyFeedback()
        val key = Key.from(event.keyCode)
        // A key press only clears the splash / charging screen; it doesn't do anything else.
        if (overlay != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            overlayKey = key
            hideOverlay()
            return true
        }
        if (overlayKey != null && overlayKey == key) {
            if (event.action == KeyEvent.ACTION_UP) { overlayKey = null; return true }
            if (event.repeatCount > 0) return true
            overlayKey = null // a fresh press: handle it normally
        }
        if (key == Key.UNKNOWN) {
            Log.i(HaLink.TAG, "unmapped keycode ${event.keyCode}")
            send("key", "key" to "UNKNOWN", "code" to event.keyCode, "press" to "short", "route" to "ui")
            return super.dispatchKeyEvent(event)
        }
        val s = screen
        val act = activeActivity()

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                downAt[key] = SystemClock.uptimeMillis()
                consumed.remove(key)
            }
            val route = routeFor(key, s, act)
            if (event.repeatCount > 0) {
                if (route == "device" && key.repeats) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRepeat >= 180) {
                        lastRepeat = now
                        reportKey(key, event, "repeat", route, s, act)
                    }
                    return true
                }
                if (key in consumed) return true
                return s?.onKey(key, event) == true || super.dispatchKeyEvent(event)
            }
            reportKey(key, event, "short", route, s, act)
            val handled = when {
                key == Key.HOME -> { mainPage(); true }
                key == Key.POWER -> { roomOff(); true }
                route == "device" -> true
                key.isVolume || key.isColor || key.isTransport -> true // never fall through to the system
                s?.onKey(key, event) == true -> true
                key == Key.BACK -> { s?.parent?.invoke()?.let { show(it) } ?: mainPage(); true }
                key.isDpad -> false
                else -> true
            }
            if (handled) {
                consumed += key
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action == KeyEvent.ACTION_UP) {
            val start = downAt.remove(key)
            if (start != null && SystemClock.uptimeMillis() - start >= LONG_PRESS_MS) {
                reportKey(key, event, "long", "ui", s, act)
                if (key == Key.HOME) openSettings()
            }
            if (consumed.remove(key)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun routeFor(key: Key, s: Screen?, act: Activity?): String {
        if (act == null || key == Key.HOME || key == Key.POWER || key == Key.VOICE) return "ui"
        if (key.isVolume || key.isColor || key.isTransport) return "device"
        if (s?.routesToDevice == true) return "device"
        return "ui"
    }

    private fun reportKey(key: Key, event: KeyEvent, press: String, route: String, s: Screen?, act: Activity?) {
        send(
            "key",
            "key" to key.name,
            "code" to event.keyCode,
            "press" to press,
            "route" to route,
            "activity" to act?.id,
            "screen" to s?.name,
        )
    }

    // ---------------------------------------------------------------- server

    override fun info(): JSONObject {
        val (name, code) = appVersion()
        return JSONObject()
            .put("app", "ar_remote")
            .put("version", name)
            .put("versionCode", code)
            .put("project_version", project?.version ?: "")
            .put("webhook_url", store.webhookUrl ?: "")
            .put("battery", if (battery >= 0) battery else JSONObject.NULL)
            .put("charging", charging)
            .put("screen", screen?.name ?: "")
            .put("room", room?.id ?: "")
            .put("self_update", true)
            .put("can_install", packageManager.canRequestPackageInstalls())
            .put("default_home", defaultHome())
            .put("other_launchers", org.json.JSONArray(otherLaunchers()))
            .put("wake_sensor", waker.method)
            .put("sensors", org.json.JSONArray(waker.sensorList()))
            .put("doze_exempt", (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName))
            .put("pickup", waker.mode)
            .put("logo", store.logoUrl ?: "")
    }

    override fun update(body: JSONObject) {
        val url = body.optString("url", "")
        updater.install(url)
    }

    override fun link(body: JSONObject) {
        val url = body.optString("webhook_url", "").trim()
        require(url.startsWith("http")) { "webhook_url missing" }
        val changed = url != store.webhookUrl
        store.webhookUrl = url
        if (changed) {
            sendBattery()
            hello()
        }
    }

    override fun project(body: JSONObject) {
        Project.parse(body) // validate before saving
        applyProject(body)
    }

    override fun state(body: JSONObject) {
        live.apply(body)
        refresh()
    }

    override fun navigate(body: JSONObject) {
        body.optStr("room")?.let { selectRoom(it) }
        val target: Screen = when (body.optString("screen")) {
            "lights" -> LightsScreen(this)
            "comfort" -> ComfortScreen(this)
            "shades" -> ShadesScreen(this)
            "favourites" -> FavouritesScreen(this)
            "rooms" -> RoomsScreen(this)
            "activity" -> activeActivity()?.let { ActivityScreen(this, it.id) } ?: HomeScreen(this)
            else -> HomeScreen(this)
        }
        if (project == null) return
        show(target)
    }

    // ---------------------------------------------------------------- device

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (pct != battery || isCharging != charging) {
                battery = pct
                charging = isCharging
                sendBattery()
                (overlay as? ChargingView)?.update(pct, isCharging)
                updateKeepOn()
            }
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    if (settings.chargingScreen) {
                        Waker.wakeScreen(this@MainActivity)
                        showCharging()
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Lifted off the charger: that's a pick-up too.
                    if (overlay is ChargingView || (overlay is ClockView && settings.clock == "charging")) hideOverlay()
                    if (settings.pickup != "off") Waker.wakeScreen(this@MainActivity)
                }
            }
        }
    }

    private fun sendBattery() {
        if (battery < 0) return
        val msg = JSONObject().put("type", "battery").put("level", battery).put("charging", charging)
        link.send(msg)
    }

    fun appVersion(): Pair<String, Int> = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        (info.versionName ?: "0") to info.versionCode
    } catch (e: Exception) {
        "0" to 0
    }

    fun ipAddress(): String {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                nif.inetAddresses.toList().forEach { addr ->
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val ip = wifi?.connectionInfo?.ipAddress ?: 0
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    companion object {
        const val PORT = 8080
        const val REQ_LOCATION = 41
        const val APK_URL = "https://github.com/marsh4200/ar_remote/releases/latest/download/ar-remote.apk"
        const val LONG_PRESS_MS = 800L
    }
}
