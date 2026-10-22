package dev.housecontrol.android.core

import org.json.JSONObject

/**
 * Something a widget, a tile or a shortcut asks the home to do. Stored with explicit values
 * (a toggle is resolved to on or off when it is made), so the app replays exactly what the
 * widget showed.
 */
data class HomeAction(val kind: String, val id: String? = null, val on: Boolean? = null, val target: Double? = null) {
    fun json() = JSONObject().apply {
        put("kind", kind)
        id?.let { put("id", it) }
        on?.let { put("on", it) }
        target?.let { put("target", it) }
    }

    companion object {
        const val LIGHT = "light"
        const val ROOM = "room"
        const val ALL_LIGHTS = "allLights"
        const val SCENE = "scene"
        const val AC_POWER = "acPower"
        const val AC_TARGET = "acTarget"

        fun from(json: JSONObject) = HomeAction(json.getString("kind"), json.string("id"), json.bool("on"), json.double("target"))
    }
}

/** A change one device has to make, as the hub's `command` takes it. */
data class DeviceChange(val kind: DeviceKind, val id: String, val patch: JSONObject, val state: JSONObject) {
    val message get() = jsonOf("kind" to kind.raw, "id" to id, "patch" to patch, "state" to state)
}

/**
 * What the widgets know about the home: the plan's rooms, lamps, ACs and scenes with the
 * state each device was last in. The panel page writes it (`web/src/widgets.js`) whenever
 * something changes; a widget that changes something applies the change here first, so it
 * can show the result at once. The JSON is exactly what the page posts.
 */
class HomeSnapshot(val json: JSONObject) {
    val name: String get() = json.optString("name", "Home")
    /** "home" / "studio" for the bundled demos, "custom" for the user's own plan. */
    val plan: String get() = json.optString("plan", "home")
    /** Tells homes apart: the plan and its name. */
    val identity get() = "$plan:$name"
    val rooms get() = json.objects("rooms")
    val lights get() = json.objects("lights")
    val acs get() = json.objects("acs")
    val scenes get() = json.objects("scenes")
    val furniture get() = json.objects("furniture")
    val scene: String? get() = json.string("scene")
    val lightsOn get() = lights.count { it.optBoolean("on") }
    val acsOn get() = acs.filter { it.optBoolean("on") }
    /** True when no device is linked to a real one. */
    val isSimulated get() = (lights + acs).all { it.string("service") == null }

    fun light(id: String) = lights.firstOrNull { it.optString("id") == id }
    fun ac(id: String) = acs.firstOrNull { it.optString("id") == id }
    fun lightsIn(room: String) = lights.filter { it.optString("room") == room }

    /** The plan fragment `DeviceHub.setLinks` reads: each device with its links. */
    val linksPlan: JSONObject
        get() {
            fun entry(device: JSONObject) = jsonOf("id" to device.optString("id")).mergeFrom(device.optJSONObject("links"))
            return jsonOf("lights" to lights.map(::entry), "acs" to acs.map(::entry))
        }

    /**
     * Applies `action` the way the panel's store does (`web/src/store.js`): the same
     * clamping, the same scene rules. Returns the action with every toggle resolved and
     * what each device has to change; a no-op returns no changes.
     */
    fun apply(action: HomeAction): Pair<HomeAction, List<DeviceChange>> {
        var resolved = action
        val changes = mutableListOf<DeviceChange>()
        when (action.kind) {
            HomeAction.LIGHT -> {
                val light = action.id?.let(::light)
                if (light != null) {
                    val on = action.on ?: !light.optBoolean("on")
                    resolved = action.copy(on = on)
                    writeLight(light, jsonOf("on" to on))?.let { changes += it; setScene(null) }
                }
            }
            HomeAction.ROOM -> if (action.id != null) {
                val inRoom = lightsIn(action.id)
                val on = action.on ?: inRoom.none { it.optBoolean("on") }
                resolved = action.copy(on = on)
                changes += inRoom.mapNotNull { writeLight(it, jsonOf("on" to on)) }
                if (changes.isNotEmpty()) setScene(null)
            }
            HomeAction.ALL_LIGHTS -> {
                val on = action.on ?: (lightsOn == 0)
                resolved = action.copy(on = on)
                changes += lights.mapNotNull { writeLight(it, jsonOf("on" to on)) }
                if (changes.isNotEmpty()) setScene(null)
            }
            HomeAction.SCENE -> {
                val definition = scenes.firstOrNull { it.optString("id") == action.id }
                if (definition != null) {
                    val lightRules = definition.optJSONObject("lights") ?: JSONObject()
                    val acRules = definition.optJSONObject("acs") ?: JSONObject()
                    for (light in lights) {
                        val patch = JSONObject().mergeFrom(lightRules.optJSONObject("*")).mergeFrom(lightRules.optJSONObject(light.optString("id")))
                        // A scene that sets a white temperature should not be overridden by a stale colour.
                        if (!patch.has("color") && patch.has("temp")) patch.put("color", JSONObject.NULL)
                        writeLight(light, patch)?.let { changes += it }
                    }
                    for (ac in acs) {
                        val patch = JSONObject().mergeFrom(acRules.optJSONObject("*")).mergeFrom(acRules.optJSONObject(ac.optString("id")))
                        if (patch.length() > 0) writeAC(ac, patch)?.let { changes += it }
                    }
                    setScene(action.id)
                }
            }
            HomeAction.AC_POWER -> {
                val ac = action.id?.let(::ac)
                if (ac != null) {
                    val on = action.on ?: !ac.optBoolean("on")
                    resolved = action.copy(on = on)
                    writeAC(ac, jsonOf("on" to on))?.let { changes += it }
                }
            }
            HomeAction.AC_TARGET -> {
                val ac = action.id?.let(::ac)
                if (ac != null && action.target != null) {
                    val clamped = action.target.roundHalfAway().coerceIn(ac.optDouble("min", 16.0), ac.optDouble("max", 30.0))
                    resolved = action.copy(target = clamped)
                    writeAC(ac, jsonOf("target" to clamped))?.let { changes += it }
                }
            }
        }
        if (changes.isNotEmpty()) json.put("updated", System.currentTimeMillis() / 1000.0)
        return resolved to changes
    }

    private fun setScene(id: String?) {
        json.put("scene", id ?: JSONObject.NULL)
    }

    private fun writeLight(light: JSONObject, patch: JSONObject): DeviceChange? {
        val caps = light.optJSONArray("capabilities").strings()
        val diff = JSONObject()
        patch.bool("on")?.let { if (it != light.optBoolean("on")) { light.put("on", it); diff.put("on", it) } }
        if ("brightness" in caps) patch.double("brightness")?.let {
            val value = it.coerceIn(0.05, 1.0)
            if (value != light.optDouble("brightness")) { light.put("brightness", value); diff.put("brightness", value) }
        }
        if ("temp" in caps) patch.double("temp")?.let {
            val value = it.coerceIn(2200.0, 6500.0)
            if (value != light.optDouble("temp")) { light.put("temp", value); diff.put("temp", value) }
            // Setting a white temperature drops the colour (store.js does the same).
            if (light.string("color") != null) { light.put("color", JSONObject.NULL); diff.put("color", JSONObject.NULL) }
        }
        if ("color" in caps && patch.has("color")) {
            val value = patch.string("color")
            if (value != light.string("color")) { light.put("color", value ?: JSONObject.NULL); diff.put("color", value ?: JSONObject.NULL) }
        }
        if (diff.length() == 0) return null
        val state = jsonOf("on" to light.optBoolean("on"), "brightness" to light.optDouble("brightness", 1.0),
            "temp" to light.optDouble("temp", 3000.0), "color" to light.string("color"))
        return DeviceChange(DeviceKind.LIGHT, light.optString("id"), diff, state)
    }

    private fun writeAC(ac: JSONObject, patch: JSONObject): DeviceChange? {
        val diff = JSONObject()
        patch.bool("on")?.let { if (it != ac.optBoolean("on")) { ac.put("on", it); diff.put("on", it) } }
        patch.double("target")?.let {
            val value = it.roundHalfAway().coerceIn(ac.optDouble("min", 16.0), ac.optDouble("max", 30.0))
            if (value != ac.optDouble("target")) { ac.put("target", value); diff.put("target", value) }
        }
        for (key in listOf("mode", "fan", "swing")) {
            patch.string(key)?.let { if (it != ac.string(key)) { ac.put(key, it); diff.put(key, it) } }
        }
        for (key in listOf("sleep", "eco")) {
            patch.bool(key)?.let { if (it != ac.bool(key)) { ac.put(key, it); diff.put(key, it) } }
        }
        if (diff.length() == 0) return null
        val state = jsonOf("on" to ac.optBoolean("on"), "target" to ac.optDouble("target", 24.0),
            "mode" to ac.optString("mode", "cool"), "fan" to ac.optString("fan", "auto"))
        for (key in listOf("swing", "sleep", "eco")) if (ac.has(key) && !ac.isNull(key)) state.put(key, ac.get(key))
        return DeviceChange(DeviceKind.AC, ac.optString("id"), diff, state)
    }

    /** The state in the shape the panel's `adopt` takes. */
    val panelState: JSONObject
        get() {
            val lightsOut = JSONObject()
            for (l in lights) {
                lightsOut.put(l.optString("id"), jsonOf("on" to l.optBoolean("on"), "brightness" to l.optDouble("brightness", 1.0),
                    "temp" to l.optDouble("temp", 3000.0), "color" to l.string("color")))
            }
            val acsOut = JSONObject()
            for (a in acs) {
                val s = jsonOf("on" to a.optBoolean("on"), "target" to a.optDouble("target", 24.0),
                    "mode" to a.optString("mode", "cool"), "fan" to a.optString("fan", "auto"))
                for (key in listOf("swing", "sleep", "eco")) if (a.has(key) && !a.isNull(key)) s.put(key, a.get(key))
                acsOut.put(a.optString("id"), s)
            }
            return jsonOf("lights" to lightsOut, "acs" to acsOut, "scene" to scene, "source" to "Widget")
        }
}
