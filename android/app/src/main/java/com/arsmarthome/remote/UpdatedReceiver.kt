package com.arsmarthome.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Android doesn't reopen an app after it updates itself, so the remote fell back
 * to the stock launcher (RosCard). This brings AR Remote straight back after every
 * update (and after a reboot, in case the stock launcher grabs the screen first).
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(HaLink.TAG, "relaunch after ${intent.action}")
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        } catch (e: Exception) {
            Log.w(HaLink.TAG, "relaunch failed: ${e.message}")
        }
    }
}
