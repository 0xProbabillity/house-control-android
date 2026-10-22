package dev.housecontrol.android.smartlife

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Drives air conditioners that are a remote control learned by an infrared blaster.
 *
 * Such an AC is not a device with a state of its own. Tuya keeps a virtual remote in
 * the cloud; every change is one key press (`PowerOn`, `PowerOff`, `M`, `T`, `F`) that
 * the blaster transmits, and the cloud works out the infrared code from the state it
 * remembers for that remote. Three things follow:
 *  - keys go out one at a time, a second apart (several in one request are refused);
 *  - nothing is transmitted while the AC is off: what changed is sent when it is switched on;
 *  - when a key is refused (code 2008) the cloud's idea of the remote usually differs
 *    from ours, e.g. it is in a mode with a fixed temperature. The rest of our state is
 *    replayed once and the key retried.
 */
class InfraredRemote {
    /** The parts of the change that did not reach the AC. */
    class Rejection(message: String, val fields: List<String>) : Exception(message)

    /** Milliseconds between two key presses of the same remote. */
    var spacing = 1000L
    private val lastKey = HashMap<String, Long>()
    /** Settings changed while the AC was off, to be sent along when it is switched on. */
    private val deferred = HashMap<String, MutableSet<String>>()

    /**
     * Brings the remote `deviceId` from where it was to `state` (the app's state with
     * `patch` already applied). `send` transmits one command; `note` receives log lines.
     */
    suspend fun apply(patch: JSONObject, state: JSONObject, deviceId: String,
                      send: suspend (JSONObject) -> Unit, note: (String) -> Unit) {
        val isOn = state.optBoolean("on", false)
        val wanted = JSONObject()
        for (field in FIELDS) if (patch.has(field)) wanted.put(field, patch.get(field))
        if (!isOn && !wanted.has("on")) {
            if (wanted.length() == 0) return
            deferred.getOrPut(deviceId) { mutableSetOf() }.addAll(wanted.keys().asSequence())
            note("off: ${wanted.keys().asSequence().sorted().joinToString(", ")} kept for the next switch-on")
            return
        }
        if (wanted.opt("on") == true) {
            deferred.remove(deviceId)?.let { kept ->
                for (field in kept) if (!wanted.has(field) && state.has(field)) wanted.put(field, state.get(field))
            }
        }
        if (wanted.opt("on") == false) deferred.remove(deviceId)

        val keys = TuyaMapper.infraredKeys(wanted)
        var index = 0
        var replayed = false
        while (index < keys.size) {
            val key = keys[index]
            try {
                press(key, deviceId, send)
                index++
            } catch (error: Exception) {
                val pending = keys.drop(index).map { it.field }
                if (tuyaCode(error) == "2008" && !replayed && isOn && key.field != "on") {
                    replayed = true
                    val rest = JSONObject()
                    for (field in FIELDS) if (state.has(field)) rest.put(field, state.get(field))
                    for (field in pending + keys.take(index).map { it.field }) rest.remove(field)
                    note("${key.field} refused (2008): replaying ${rest.keys().asSequence().sorted().joinToString(", ")}")
                    for (other in TuyaMapper.infraredKeys(rest)) runCatching { press(other, deviceId, send) }
                    continue
                }
                val reason = if (tuyaCode(error) == "2008") "this remote has no infrared code for that setting (Tuya code 2008)"
                else error.message ?: "Smart Life request failed"
                throw Rejection(reason, pending)
            }
        }
    }

    private suspend fun press(key: TuyaMapper.InfraredKey, deviceId: String, send: suspend (JSONObject) -> Unit) {
        lastKey[deviceId]?.let { last ->
            val wait = spacing - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
        }
        try {
            send(key.command)
        } finally {
            lastKey[deviceId] = System.currentTimeMillis()
        }
    }

    private companion object {
        val FIELDS = listOf("on", "mode", "target", "fan")
    }
}
