package com.arsmarthome.remote

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Link URL, last room and the programmed project survive restarts and reboots. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ar_remote", Context.MODE_PRIVATE)
    private val projectFile = File(context.filesDir, "project.json")

    var webhookUrl: String?
        get() = prefs.getString("webhook_url", null)
        set(value) = prefs.edit().putString("webhook_url", value).apply()

    var roomId: String?
        get() = prefs.getString("room", null)
        set(value) = prefs.edit().putString("room", value).apply()

    /** Path of the logo last downloaded (see Logo). */
    var logoUrl: String?
        get() = prefs.getString("logo_url", null)
        set(value) = prefs.edit().putString("logo_url", value).apply()

    /** Asked once for the battery-optimisation (Doze) exemption. */
    var askedDoze: Boolean
        get() = prefs.getBoolean("asked_doze", false)
        set(value) = prefs.edit().putBoolean("asked_doze", value).apply()

    fun loadProject(): Project? = try {
        if (projectFile.exists()) Project.parse(JSONObject(projectFile.readText())) else null
    } catch (e: Exception) {
        null
    }

    fun saveProject(json: JSONObject) {
        val tmp = File(projectFile.parentFile, "project.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(projectFile)
    }
}
