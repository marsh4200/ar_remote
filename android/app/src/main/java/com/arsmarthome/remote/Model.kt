package com.arsmarthome.remote

import org.json.JSONArray
import org.json.JSONObject

data class Item(val entity: String, val name: String)

data class LightItem(val entity: String, val name: String, val dimmable: Boolean)

data class Activity(
    val id: String,
    val name: String,
    val kind: String,
    val source: String?,
    val volume: String?,
    val custom: List<String>,
    val colors: Map<String, String>,
    val hasDpad: Boolean,
    val logo: String? = null,
)

data class Room(
    val id: String,
    val name: String,
    val temperature: String?,
    val activities: List<Activity>,
    val lights: List<LightItem>,
    val scenes: List<Item>,
    val climate: Item?,
    val covers: List<Item>,
    val favourites: List<Item>,
) {
    fun activity(id: String?): Activity? = activities.firstOrNull { it.id == id }
    fun activitiesOf(kind: String): List<Activity> = activities.filter { it.kind == kind }
}

/** Remote settings from the Composer (charging screen, wake on pick-up, logo). */
data class DeviceSettings(
    val logo: String? = null,
    val splashSeconds: Int = 2,
    val chargingScreen: Boolean = true,
    val pickup: String = "auto",
    val pickupSensitivity: String = "normal",
) {
    companion object {
        fun parse(d: JSONObject?): DeviceSettings {
            if (d == null) return DeviceSettings()
            return DeviceSettings(
                logo = d.optStr("logo"),
                splashSeconds = d.optInt("splash_seconds", 2).coerceIn(0, 10),
                chargingScreen = d.optBoolean("charging_screen", true),
                pickup = d.optString("pickup", "auto"),
                pickupSensitivity = d.optString("pickup_sensitivity", "normal"),
            )
        }
    }
}

data class Project(
    val version: String,
    val defaultRoom: String?,
    val rooms: List<Room>,
    val device: DeviceSettings = DeviceSettings(),
) {
    fun room(id: String?): Room? = rooms.firstOrNull { it.id == id }

    companion object {
        fun parse(json: JSONObject): Project = Project(
            version = json.optString("version", ""),
            defaultRoom = json.optStr("default_room"),
            rooms = json.optJSONArray("rooms").objects().map { parseRoom(it) },
            device = DeviceSettings.parse(json.optJSONObject("device")),
        )

        private fun parseRoom(r: JSONObject): Room = Room(
            id = r.getString("id"),
            name = r.optString("name", r.getString("id")),
            temperature = r.optStr("temperature"),
            activities = r.optJSONArray("activities").objects().map { a ->
                val colors = a.optJSONObject("colors")
                Activity(
                    id = a.getString("id"),
                    name = a.optString("name", a.getString("id")),
                    kind = a.optString("kind", "watch"),
                    source = a.optStr("source"),
                    volume = a.optStr("volume"),
                    custom = a.optJSONArray("custom").strings(),
                    colors = listOf("red", "green", "blue", "yellow")
                        .associateWith { colors?.optString(it, "") ?: "" },
                    hasDpad = a.optBoolean("has_dpad", false),
                    logo = a.optStr("logo"),
                )
            },
            lights = r.optJSONArray("lights").objects().map {
                LightItem(it.getString("entity"), it.optString("name"), it.optBoolean("dimmable", true))
            },
            scenes = items(r.optJSONArray("scenes")),
            climate = r.optJSONObject("climate")?.let { Item(it.getString("entity"), it.optString("name")) },
            covers = items(r.optJSONArray("covers")),
            favourites = items(r.optJSONArray("favourites")),
        )

        private fun items(arr: JSONArray?): List<Item> =
            arr.objects().map { Item(it.getString("entity"), it.optString("name")) }
    }
}

/** Live entity state pushed by HA (docs/PROTOCOL.md section 4). */
class LiveState {
    val entities = HashMap<String, JSONObject>()
    val roomActivity = HashMap<String, String?>()

    fun apply(json: JSONObject) {
        if (json.optBoolean("full", false)) {
            entities.clear()
            roomActivity.clear()
        }
        json.optJSONObject("entities")?.let { ents ->
            ents.keys().forEach { id -> ents.optJSONObject(id)?.let { entities[id] = it } }
        }
        json.optJSONObject("rooms")?.let { rooms ->
            rooms.keys().forEach { id ->
                roomActivity[id] = rooms.optJSONObject(id)?.optStr("activity")
            }
        }
    }

    fun get(entity: String?): JSONObject? = entity?.let { entities[it] }
    fun state(entity: String?): String = get(entity)?.optString("s", "") ?: ""
    fun isOn(entity: String?): Boolean =
        state(entity) !in setOf("", "off", "unavailable", "unknown", "standby", "closed")
}

fun JSONObject.optStr(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it, "") }
