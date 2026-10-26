package com.arsmarthome.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Wakes the screen when the remote is picked up.
 *
 * Only listens while the screen is off.
 *  - "auto" (default): the accelerometer, with the CPU kept awake just enough to
 *    read it. Works on every remote; also arms the pick-up gesture sensor if present.
 *  - "saver": low-power sensors only (pick-up gesture, wake-up accelerometer,
 *    significant motion). Better battery, but many remotes don't have them.
 *  - "off".
 * [method] says what is in use; it's reported in /info as wake_sensor and shown
 * in Home Assistant.
 */
class Waker(private val ctx: Context, private val onPickup: () -> Unit) : SensorEventListener {

    private val sensors = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val main = Handler(Looper.getMainLooper())

    var mode = "auto" // off | auto | saver
        private set
    private var threshold = 1.2f // m/s² away from resting gravity
    var method = "waits for the screen to go off"
        private set

    private var armed = false
    private var armedAt = 0L
    private var keepAwake: PowerManager.WakeLock? = null
    private var trigger: Sensor? = null
    private val gravity = FloatArray(3)
    private var samples = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> arm()
                Intent.ACTION_SCREEN_ON -> disarm()
            }
        }
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            // One-shot sensors disarm themselves; re-arm happens on the next screen off.
            fire()
        }
    }

    fun start() {
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        if (!power.isInteractive) arm()
    }

    fun stop() {
        try { ctx.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        disarm()
    }

    /** From the Composer: mode off/auto/saver, sensitivity low/normal/high. */
    fun configure(newMode: String, sensitivity: String) {
        mode = when (newMode) {
            "off", "saver" -> newMode
            else -> "auto" // "always" from 2.1 means the same as auto now
        }
        threshold = when (sensitivity) {
            "high" -> 0.6f
            "low" -> 2.2f
            else -> 1.2f
        }
        if (armed || !power.isInteractive) {
            disarm()
            if (!power.isInteractive) arm()
        }
    }

    private fun arm() {
        disarm()
        if (mode == "off") { method = "off"; return }
        armedAt = SystemClock.uptimeMillis()
        samples = 0

        val pickUp = sensors.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.stringType == "android.sensor.pick_up_gesture" }
        val pickUpArmed = pickUp != null && pickUp.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT &&
            sensors.requestTriggerSensor(triggerListener, pickUp)
        if (pickUpArmed) trigger = pickUp

        if (mode == "auto") {
            val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                keepAwake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ar_remote:pickup").apply { acquire() }
                if (sensors.registerListener(this, accel, 100_000)) {
                    method = if (pickUpArmed) "accelerometer + pick-up gesture" else "accelerometer"
                    armed = true; return
                }
                keepAwake?.release(); keepAwake = null
            }
        }
        if (pickUpArmed) { method = "pick-up gesture"; armed = true; return }

        val wakeAccel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        if (wakeAccel != null && sensors.registerListener(this, wakeAccel, 100_000, 250_000)) {
            method = "wake-up accelerometer"; armed = true; return
        }
        val motion = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (motion != null && sensors.requestTriggerSensor(triggerListener, motion)) {
            trigger = motion; method = "significant motion"; armed = true; return
        }
        method = if (mode == "saver") "none (no low-power sensor: use Auto)" else "none (no motion sensor)"
        Log.i(HaLink.TAG, "wake on pick-up: $method")
    }

    /** Motion sensors on this remote, for /info (helps when pick-up doesn't work). */
    fun sensorList(): List<String> = sensors.getSensorList(Sensor.TYPE_ALL)
        .filter {
            it.type == Sensor.TYPE_ACCELEROMETER || it.type == Sensor.TYPE_SIGNIFICANT_MOTION ||
                it.type == Sensor.TYPE_GYROSCOPE || it.type == Sensor.TYPE_LINEAR_ACCELERATION ||
                it.stringType.contains("gesture") || it.stringType.contains("motion")
        }
        .map { "${it.name} (${it.stringType}${if (it.isWakeUpSensor) ", wake-up" else ""})" }

    private fun disarm() {
        sensors.unregisterListener(this)
        trigger?.let { sensors.cancelTriggerSensor(triggerListener, it) }
        trigger = null
        keepAwake?.let { if (it.isHeld) it.release() }
        keepAwake = null
        armed = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!armed) return
        val v = event.values
        if (samples < 5) {
            // Prime the resting gravity vector.
            for (i in 0..2) gravity[i] = if (samples == 0) v[i] else gravity[i] * 0.6f + v[i] * 0.4f
            samples++
            return
        }
        val dx = v[0] - gravity[0]
        val dy = v[1] - gravity[1]
        val dz = v[2] - gravity[2]
        for (i in 0..2) gravity[i] = gravity[i] * 0.92f + v[i] * 0.08f
        // Ignore the moment the screen goes off (it was just put down).
        if (SystemClock.uptimeMillis() - armedAt < 2000) return
        if (sqrt(dx * dx + dy * dy + dz * dz) > threshold) fire()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun fire() {
        armedAt = SystemClock.uptimeMillis() - 1000 // at most one wake per second
        main.post {
            if (!power.isInteractive) onPickup()
        }
    }

    companion object {
        /** Turn the screen on (the normal screen timeout applies afterwards). */
        @Suppress("DEPRECATION")
        fun wakeScreen(ctx: Context) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            try {
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "ar_remote:wake",
                ).acquire(3000)
            } catch (e: Exception) {
                Log.w(HaLink.TAG, "wake failed: ${e.message}")
            }
        }
    }
}
