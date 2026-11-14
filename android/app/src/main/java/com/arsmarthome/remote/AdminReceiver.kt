package com.arsmarthome.remote

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android only installs an update without the "Install" prompt for the device
 * owner. tools/install-usb makes AR Remote the device owner once, over USB
 * (`dpm set-device-owner com.arsmarthome.remote/.AdminReceiver`); after that an
 * update pushed from Home Assistant installs by itself. The app uses no device
 * policies: this receiver only has to exist.
 */
class AdminReceiver : DeviceAdminReceiver()

/** True when updates install without a tap. */
fun Context.isDeviceOwner(): Boolean = try {
    (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isDeviceOwnerApp(packageName)
} catch (e: Exception) {
    false
}

/**
 * Gives device ownership up again, so the app can be uninstalled (Android
 * refuses to remove a device owner). Only ADB can send this:
 * `adb shell am broadcast -n com.arsmarthome.remote/.OwnerReceiver -a com.arsmarthome.remote.RELEASE_OWNER`
 */
class OwnerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(context.packageName)
            }
            dpm.removeActiveAdmin(ComponentName(context, AdminReceiver::class.java))
            Log.i(HaLink.TAG, "device owner released")
        } catch (e: Exception) {
            Log.w(HaLink.TAG, "release owner: ${e.message}")
        }
    }

    companion object {
        const val ACTION = "com.arsmarthome.remote.RELEASE_OWNER"
    }
}
