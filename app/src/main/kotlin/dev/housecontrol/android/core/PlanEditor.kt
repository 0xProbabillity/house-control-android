package dev.housecontrol.android.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Small edits to a floor plan that the phone makes without the drawing editor: linking a
 * lamp or an AC to a real device, adding real devices to a room, starting a new home.
 * Works on the plan exactly as stored, the same form the web editor edits, and follows its
 * rules (`web/src/editor/connections.js`, `web/src/config.js`).
 */
class PlanEditor(plan: JSONObject) {
    val plan: JSONObject = plan.copy()

    /** A lamp or an AC of the plan, for lists. `link.service` is "smartlife" for a Smart Life link. */
    data class Item(
        val id: String, val kind: DeviceKind, val name: String, val room: String, val roomName: String,
        val link: DeviceRef?, val sensor: DeviceRef?,
    )

    data class DeviceRef(val service: String, val id: String)

    data class Room(val id: String, val name: String)

    // MARK: - Reading

    val name: String get() = plan.string("name") ?: "Home"

    val rooms: List<Room>
        get() = plan.objects("rooms").mapNotNull { room ->
            val id = room.string("id") ?: return@mapNotNull null
            Room(id, room.string("name") ?: id)
        }

    val items: List<Item>
        get() {
            val names = rooms.associate { it.id to it.name }
            val out = mutableListOf<Item>()
            for ((kind, key) in listOf(DeviceKind.LIGHT to "lights", DeviceKind.AC to "acs")) {
                for (device in plan.objects(key)) {
                    val id = device.string("id") ?: continue
                    val room = device.optString("room")
                    out += Item(id, kind, device.string("name") ?: id, room, names[room] ?: room, planLink(device), planSensor(device))
                }
            }
            return out
        }

    /** Real devices already used by the plan, with the name of what uses them. */
    val usedDevices: Map<DeviceRef, String>
        get() {
            val out = HashMap<DeviceRef, String>()
            for (item in items) {
                item.link?.let { out[it] = item.name }
                item.sensor?.let { out[it] = "${item.name} · room" }
            }
            return out
        }

    // MARK: - Linking

    private fun find(id: String): JSONObject? {
        for (key in listOf("lights", "acs")) plan.objects(key).firstOrNull { it.optString("id") == id }?.let { return it }
        return null
    }

    /**
     * Links the lamp or AC `id` to `device` (a device as `DeviceHub.allDevices` lists it),
     * or unlinks it (null). A lamp takes over what the device can do, an AC its range.
     */
    fun link(id: String, device: JSONObject?) {
        val item = find(id) ?: return
        item.remove("link")
        item.optJSONObject("tuya")?.let { tuya ->
            tuya.remove("deviceId")
            if (tuya.length() == 0) item.remove("tuya")
        }
        val service = device?.string("service") ?: return
        val target = device.string("id") ?: return
        if (service == "smartlife") {
            val tuya = item.optJSONObject("tuya") ?: JSONObject().also { item.put("tuya", it) }
            tuya.put("deviceId", target)
        } else {
            item.put("link", jsonOf("service" to service, "id" to target))
            // A Smart Life thermometer only works beside a Smart Life AC.
            item.remove("tuya")
        }
        val isAC = item.has("wall")
        if (!isAC) device.optJSONArray("capabilities")?.let { item.put("capabilities", JSONArray(it.strings())) }
        val min = device.double("min")
        val max = device.double("max")
        if (isAC && min != null && max != null) {
            item.put("min", min.roundHalfAway().toInt())
            item.put("max", max.roundHalfAway().toInt())
        }
    }

    /** Sets the thermometer of the AC `id` (a device with `kind` "sensor"), or removes it. */
    fun setSensor(id: String, device: JSONObject?) {
        val item = find(id) ?: return
        item.remove("sensor")
        item.optJSONObject("tuya")?.let { tuya ->
            tuya.remove("sensorId")
            if (tuya.length() == 0) item.remove("tuya")
        }
        val service = device?.string("service") ?: return
        val target = device.string("id") ?: return
        if (service == "smartlife") {
            val tuya = item.optJSONObject("tuya") ?: JSONObject().also { item.put("tuya", it) }
            tuya.put("sensorId", target)
        } else {
            item.put("sensor", jsonOf("service" to service, "id" to target))
        }
    }

    // MARK: - Adding devices

    private val allIds: MutableSet<String>
        get() = listOf("rooms", "lights", "acs", "scenes").flatMap { key -> plan.objects(key).mapNotNull { it.string("id") } }.toMutableSet()

    private fun uniqueId(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base$i" in taken) i++
        return "$base$i"
    }

    private fun rect(room: String): List<Double>? {
        val rect = plan.objects("rooms").firstOrNull { it.optString("id") == room }?.optJSONArray("rect") ?: return null
        if (rect.length() != 4) return null
        return (0 until 4).map { rect.optDouble(it) }
    }

    /**
     * Adds real devices (as `DeviceHub.allDevices` lists them) to `room` as linked lamps
     * and ACs, spread over the room so none stands on another. Sensors are skipped.
     * Returns the ids of the new items.
     */
    fun add(devices: List<JSONObject>, room: String): List<String> {
        val (x, z, w, d) = rect(room) ?: return emptyList()
        val taken = allIds
        val lights = plan.optJSONArray("lights") ?: JSONArray()
        val acs = plan.optJSONArray("acs") ?: JSONArray()
        val newLights = devices.filter { it.optString("kind") == "light" }
        val newACs = devices.filter { it.optString("kind") == "ac" }
        val added = mutableListOf<String>()

        // Lamps on a grid inside the room, away from the lamps already there.
        val existing = lights.objects().count { it.optString("room") == room }
        val total = existing + newLights.size
        val columns = maxOf(1, ceil(sqrt(total.toDouble())).toInt())
        val rows = maxOf(1, ceil(total.toDouble() / columns).toInt())
        newLights.forEachIndexed { n, device ->
            val slot = existing + n
            val cx = x + w * ((slot % columns) + 0.5) / columns
            val cz = z + d * ((slot / columns) + 0.5) / rows
            val name = device.string("name") ?: "Light"
            val id = uniqueId("$room.light", taken).also { taken += it }
            val light = jsonOf("id" to id, "name" to name, "room" to room, "type" to fixture(name),
                "pos" to listOf(fix(cx), fix(cz)), "capabilities" to (device.optJSONArray("capabilities")?.strings() ?: emptyList()))
            if (light.optString("type") == "strip") light.put("length", 1.0)
            lights.put(light)
            added += id
        }

        // ACs along the north wall, then the others, spaced out.
        val walls = listOf("north", "south", "west", "east")
        val existingACs = acs.objects().count { it.optString("room") == room }
        newACs.forEachIndexed { n, device ->
            val slot = existingACs + n
            val wall = walls[slot % 4]
            val along = (slot / 4 + 1).toDouble() / (slot / 4 + 2)
            val pos = when (wall) {
                "north" -> listOf(x + w * along, z)
                "south" -> listOf(x + w * along, z + d)
                "west" -> listOf(x, z + d * along)
                else -> listOf(x + w, z + d * along)
            }
            val id = uniqueId("$room.ac", taken).also { taken += it }
            val ac = jsonOf("id" to id, "name" to (device.string("name") ?: "AC"), "room" to room, "wall" to wall,
                "pos" to pos.map(::fix), "y" to 2.2)
            val min = device.double("min")
            val max = device.double("max")
            if (min != null && max != null) {
                ac.put("min", min.roundHalfAway().toInt())
                ac.put("max", max.roundHalfAway().toInt())
            }
            acs.put(ac)
            added += id
        }
        plan.put("lights", lights)
        plan.put("acs", acs)
        // Link each new item to its device.
        added.zip(newLights + newACs).forEach { (id, device) -> link(id, device) }
        return added
    }

    /** Removes a lamp or an AC, and its mentions in the scenes. */
    fun remove(id: String) {
        for (key in listOf("lights", "acs")) {
            val list = plan.optJSONArray(key) ?: continue
            val index = (0 until list.length()).firstOrNull { list.optJSONObject(it)?.optString("id") == id } ?: continue
            list.remove(index)
            for (scene in plan.objects("scenes")) {
                for (map in listOf("lights", "acs")) scene.optJSONObject(map)?.remove(id)
            }
            return
        }
    }

    /** Adds a room east of the others; returns its id. */
    fun addRoom(name: String, width: Double = 4.0, depth: Double = 3.5): String {
        val rooms = plan.optJSONArray("rooms") ?: JSONArray().also { plan.put("rooms", it) }
        val rects = rooms.objects().mapNotNull { r -> r.optJSONArray("rect")?.takeIf { it.length() == 4 }?.let { a -> (0 until 4).map { a.optDouble(it) } } }
        val maxX = rects.maxOfOrNull { it[0] + it[2] } ?: 0.0
        val minZ = rects.minOfOrNull { it[1] } ?: 0.0
        val id = uniqueId(slug(name), allIds)
        rooms.put(jsonOf("id" to id, "name" to name, "rect" to listOf(fix(maxX), fix(minZ), width, depth), "floor" to "oak"))
        return id
    }

    companion object {
        private fun fix(v: Double) = (v * 1000).roundHalfAway() / 1000

        /** The fixture a lamp probably is, from its name. */
        fun fixture(name: String): String {
            val n = name.lowercase()
            return when {
                n.contains("strip") || n.contains("лент") -> "strip"
                n.contains("floor") || n.contains("торшер") -> "floor"
                listOf("desk", "table", "bedside", "night", "насто", "ночн").any { n.contains(it) } -> "table"
                n.contains("pendant") || n.contains("люстр") -> "pendant"
                n.contains("spot") -> "spots"
                else -> "ceiling"
            }
        }

        fun slug(text: String): String {
            val s = text.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
            return s.split("-").filter { it.isNotEmpty() }.joinToString("-").ifEmpty { "room" }
        }

        /** A new home with one room, ready to be drawn further: the starting point of a home set up on the phone. */
        fun blank(name: String): JSONObject = jsonOf(
            "version" to 1,
            "name" to name,
            "wallHeight" to 2.6,
            "interiorWallHeight" to 1.25,
            "camera" to jsonOf("azimuth" to 34, "elevation" to 47),
            "rooms" to listOf(jsonOf("id" to "living", "name" to "Living room", "rect" to listOf(0.0, 0.0, 5.0, 4.0), "floor" to "oak")),
            "openings" to JSONArray(),
            "furniture" to JSONArray(),
            "lights" to JSONArray(),
            "acs" to JSONArray(),
            "scenes" to listOf(
                jsonOf("id" to "bright", "name" to "Bright", "icon" to "sun", "lights" to jsonOf("*" to jsonOf("on" to true, "brightness" to 1))),
                jsonOf("id" to "off", "name" to "All off", "icon" to "moon", "lights" to jsonOf("*" to jsonOf("on" to false)),
                    "acs" to jsonOf("*" to jsonOf("on" to false))),
            ),
        )
    }
}

/** The plan's link of a lamp or AC to a real device, for lists (`service` "smartlife" for Smart Life). */
fun planLink(device: JSONObject): PlanEditor.DeviceRef? {
    val link = device.optJSONObject("link")
    val service = link?.string("service")
    val id = link?.string("id")
    if (service != null && id != null) return PlanEditor.DeviceRef(service, id)
    return device.optJSONObject("tuya")?.string("deviceId")?.let { PlanEditor.DeviceRef("smartlife", it) }
}

/** An AC's thermometer. */
fun planSensor(device: JSONObject): PlanEditor.DeviceRef? {
    val sensor = device.optJSONObject("sensor")
    val service = sensor?.string("service")
    val id = sensor?.string("id")
    if (service != null && id != null) return PlanEditor.DeviceRef(service, id)
    return device.optJSONObject("tuya")?.string("sensorId")?.let { PlanEditor.DeviceRef("smartlife", it) }
}
