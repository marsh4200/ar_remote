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

    // ---- Choices made on the remote's own settings panel (win over HA's defaults).

    /** null = follow Home Assistant. */
    fun override(key: String): Boolean? =
        if (prefs.contains("o_$key")) prefs.getBoolean("o_$key", false) else null

    fun setOverride(key: String, value: Boolean) = prefs.edit().putBoolean("o_$key", value).apply()

    fun flag(key: String, default: Boolean): Boolean = prefs.getBoolean("f_$key", default)
    fun setFlag(key: String, value: Boolean) = prefs.edit().putBoolean("f_$key", value).apply()

    /** Screen brightness 5..100 (applies to the AR Remote screen). */
    var brightness: Int
        get() = prefs.getInt("brightness", 80)
        set(value) = prefs.edit().putInt("brightness", value.coerceIn(5, 100)).apply()

    /**
     * Ready for a new site: forget the Home Assistant link, the programmed rooms,
     * the logo and activity logos, and every choice made on the settings panel.
     * Wi-Fi and the app's permissions stay.
     */
    fun wipe(filesDir: File) {
        prefs.edit().clear().commit()
        for (name in listOf("project.json", "project.json.tmp", "logo.img", "logo.tmp")) {
            File(filesDir, name).delete()
        }
        File(filesDir, "icons").deleteRecursively()
    }

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
