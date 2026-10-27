package com.arsmarthome.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/** A network from a scan. */
data class WifiNet(val ssid: String, val level: Int, val secure: Boolean)

/**
 * Wi-Fi for the settings panel. Android 9 and older: the app scans and joins
 * the network itself. Android 10+: Google blocks that, so joining goes through
 * Android's own "Save this network?" dialog (11+) or Wi-Fi panel (10).
 */
class Wifi(private val ctx: Context) {
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val canJoinDirectly: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Current network name, or null when not connected. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? {
        val info = wm.connectionInfo ?: return null
        if (info.networkId == -1) return null
        val s = info.ssid?.trim('"') ?: return null
        return if (s.isEmpty() || s == "<unknown ssid>") null else s
    }

    fun isOn(): Boolean = wm.isWifiEnabled

    @Suppress("DEPRECATION")
    fun turnOn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) wm.isWifiEnabled = true
        else openSystemWifi()
    }

    /** Scan; [onResults] gets the networks, strongest first (deduplicated by name). */
    @Suppress("DEPRECATION")
    fun scan(onResults: (List<WifiNet>) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                onResults(results())
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val started = try { wm.startScan() } catch (e: Exception) { false }
        if (!started) {
            // Throttled or not allowed: show what Android already knows.
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            onResults(results())
        }
    }

    private fun results(): List<WifiNet> = try {
        wm.scanResults
            .filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level }!!
                WifiNet(ssid, WifiManager.calculateSignalLevel(best.level, 4), isSecure(best))
            }
            .sortedByDescending { it.level }
    } catch (e: SecurityException) {
        Log.w(HaLink.TAG, "wifi scan: ${e.message}")
        emptyList()
    }

    private fun isSecure(r: ScanResult): Boolean {
        val c = r.capabilities ?: return false
        return c.contains("WPA") || c.contains("WEP") || c.contains("SAE") || c.contains("PSK") || c.contains("EAP")
    }

    /**
     * Join [net]. True when the remote handled it itself (Android 9 and older);
     * false when Android's own screen was opened to finish it.
     */
    @Suppress("DEPRECATION")
    fun join(net: WifiNet, password: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val conf = WifiConfiguration().apply {
                SSID = "\"${net.ssid}\""
                if (net.secure) preSharedKey = "\"$password\""
                else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            // Replace an older saved copy of the same network.
            try {
                wm.configuredNetworks?.filter { it.SSID == conf.SSID }?.forEach { wm.removeNetwork(it.networkId) }
            } catch (_: SecurityException) {}
            val id = wm.addNetwork(conf)
            if (id == -1) return false
            wm.disconnect()
            wm.enableNetwork(id, true)
            wm.reconnect()
            wm.saveConfiguration()
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: "Save this network?" dialog, pre-filled with the password.
            val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
                .setSsid(net.ssid)
                .apply { if (net.secure) setWpa2Passphrase(password) }
                .build()
            val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(intent); return false } catch (_: Exception) {}
        }
        openSystemWifi()
        return false
    }

    fun openSystemWifi() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        try {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}
