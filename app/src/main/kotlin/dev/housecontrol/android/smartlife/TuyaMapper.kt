package dev.housecontrol.android.smartlife

import dev.housecontrol.android.core.bool
import dev.housecontrol.android.core.double
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.roundHalfAway
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** (min, max) of each numeric data point, from the device specification when known. */
typealias Ranges = Map<String, Pair<Double, Double>>

/**
 * Translates between the app's device state and Tuya's "standard instruction set".
 * Devices differ in which data points they expose and in their value ranges, so every
 * mapping checks the codes the device actually reported and silently skips what it cannot do.
 */
object TuyaMapper {
    private val acModes = linkedMapOf("cool" to "cold", "heat" to "hot", "dry" to "wet", "fan" to "wind", "auto" to "auto")
    private val acFans = linkedMapOf("auto" to "auto", "low" to "low", "medium" to "mid", "high" to "high", "turbo" to "strong")
    // Infrared ACs (a remote learned by an IR blaster) number their modes and fan speeds.
    private val irModes = mapOf("cool" to 0, "heat" to 1, "auto" to 2, "fan" to 3, "dry" to 4)
    private val irFans = mapOf("auto" to 0, "low" to 1, "medium" to 2, "high" to 3, "turbo" to 3)

    private fun pick(candidates: List<String>, codes: Set<String>, override: Any?): String? {
        (override as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        return candidates.firstOrNull { it in codes }
    }

    /**
     * Range of a brightness / temperature point: the specification wins; otherwise the
     * `_v2` points are 0…1000 and the legacy ones 0…255.
     */
    private fun range(code: String, ranges: Ranges, floor: Double = 0.0): Pair<Double, Double> {
        ranges[code]?.let { if (it.second > it.first) return it }
        return if (code.endsWith("_v2")) (if (floor > 0) 10.0 else 0.0) to 1000.0 else floor to 255.0
    }

    private fun command(code: String, value: Any) = jsonOf("code" to code, "value" to value)

    fun lightCommands(patch: JSONObject, state: JSONObject, link: JSONObject, codes: Set<String>, ranges: Ranges = emptyMap()): List<JSONObject> {
        val custom = link.optJSONObject("codes") ?: JSONObject()
        val out = mutableListOf<JSONObject>()
        patch.bool("on")?.let { on ->
            // A plain plug only has a switch; assume switch_1 when the device has not reported yet.
            out += command(pick(listOf("switch_led", "switch_1", "switch"), codes, custom.opt("switch")) ?: "switch_1", on)
        }
        val brightness = patch.double("brightness")
        val brightCode = pick(listOf("bright_value_v2", "bright_value"), codes, null)
        if (brightness != null && brightCode != null) {
            val (lo, hi) = range(brightCode, ranges, floor = 25.0)
            out += command(brightCode, (lo + brightness * (hi - lo)).roundHalfAway().toInt())
        }
        val kelvin = patch.double("temp")
        val tempCode = pick(listOf("temp_value_v2", "temp_value"), codes, null)
        if (kelvin != null && tempCode != null) {
            val t = max(0.0, min(1.0, (kelvin - 2200) / (6500 - 2200)))
            val (lo, hi) = range(tempCode, ranges)
            if ("work_mode" in codes) out += command("work_mode", "white")
            out += command(tempCode, (lo + t * (hi - lo)).roundHalfAway().toInt())
        }
        val hsv = (patch.opt("color") as? String)?.let(::hsv)
        val colourCode = pick(listOf("colour_data_v2", "colour_data"), codes, null)
        if (hsv != null && colourCode != null) {
            val (h, s, _) = hsv
            // Saturation and value share the brightness scale of the same generation.
            val scale = if (range(if (colourCode == "colour_data_v2") "bright_value_v2" else "bright_value", ranges).second > 255) 1000.0 else 255.0
            val v = max(0.01, state.double("brightness") ?: 1.0)
            if ("work_mode" in codes) out += command("work_mode", "colour")
            // Sent as a JSON string, which is also how the device reports it.
            out += command(colourCode, "{\"h\":${h.toInt()},\"s\":${(s * scale).toInt()},\"v\":${(v * scale).toInt()}}")
        }
        return out
    }

    fun lightState(status: List<JSONObject>, ranges: Ranges = emptyMap()): JSONObject {
        val out = JSONObject()
        for (dp in status) {
            val code = dp.optString("code")
            val value = dp.opt("value") ?: continue
            when (code) {
                "switch_led", "switch_1", "switch" -> if (!out.has("on") && value is Boolean) out.put("on", value)
                "bright_value_v2", "bright_value" -> value.asNumber()?.let { v ->
                    val (lo, hi) = range(code, ranges, floor = 25.0)
                    out.put("brightness", max(0.05, min(1.0, (v - lo) / (hi - lo))))
                }
                "temp_value_v2", "temp_value" -> value.asNumber()?.let { v ->
                    val (lo, hi) = range(code, ranges)
                    out.put("temp", 2200 + max(0.0, min(1.0, (v - lo) / (hi - lo))) * 4300)
                }
            }
        }
        return out
    }

    /**
     * True for an AC that is a remote learned by an infrared blaster. Those take the
     * "PowerOn / PowerOff / T / M / F" key set and never report their state back.
     */
    fun isInfraredAC(codes: Set<String>) = "PowerOn" in codes || "PowerOff" in codes

    /** One key press of an infrared remote, and the piece of app state it carries. */
    data class InfraredKey(val field: String, val command: JSONObject)

    /**
     * The key presses that bring an infrared AC to `patch`. Order matters for a remote:
     * power first, then mode, then the set-point and fan.
     */
    fun infraredKeys(patch: JSONObject): List<InfraredKey> {
        val out = mutableListOf<InfraredKey>()
        patch.bool("on")?.let { on ->
            val key = if (on) "PowerOn" else "PowerOff"
            out += InfraredKey("on", command(key, key))
        }
        (patch.opt("mode") as? String)?.let { irModes[it] }?.let { out += InfraredKey("mode", command("M", it)) }
        patch.double("target")?.let { out += InfraredKey("target", command("T", it.toInt())) }
        (patch.opt("fan") as? String)?.let { irFans[it] }?.let { out += InfraredKey("fan", command("F", it)) }
        return out
    }

    /**
     * What the panel may offer for a linked AC. Controls the device has no function for
     * are hidden instead of pretending to work. Null until the functions are known.
     */
    fun acFeatures(codes: Set<String>, ranges: Ranges): JSONObject? {
        if (codes.isEmpty()) return null
        val allModes = listOf("cool", "heat", "dry", "fan", "auto")
        if (isInfraredAC(codes)) {
            val m = ranges["M"] ?: (0.0 to 4.0)
            val f = ranges["F"] ?: (0.0 to 3.0)
            return jsonOf(
                "infrared" to true,
                "modes" to allModes.filter { (irModes[it] ?: 99).toDouble().let { v -> v >= m.first && v <= m.second } },
                // Turbo shares the top speed with High on a remote, so it is not offered twice.
                "fans" to listOf("auto", "low", "medium", "high").filter { (irFans[it] ?: 99).toDouble().let { v -> v >= f.first && v <= f.second } },
                "sleep" to false, "eco" to false, "swing" to false,
            )
        }
        return jsonOf(
            "infrared" to false,
            "modes" to if ("mode" in codes) allModes else emptyList(),
            "fans" to if ("fan_speed_enum" in codes || "windspeed" in codes) listOf("auto", "low", "medium", "high", "turbo") else emptyList(),
            "sleep" to ("sleep" in codes), "eco" to ("eco" in codes), "swing" to false,
        )
    }

    fun acCommands(patch: JSONObject, link: JSONObject, codes: Set<String>): List<JSONObject> {
        if (isInfraredAC(codes)) return infraredKeys(patch).map { it.command }
        val custom = link.optJSONObject("codes") ?: JSONObject()
        val out = mutableListOf<JSONObject>()
        patch.bool("on")?.let { out += command(pick(listOf("switch", "power", "switch_1"), codes, custom.opt("switch")) ?: "switch", it) }
        patch.double("target")?.let { out += command(pick(listOf("temp_set"), codes, custom.opt("target")) ?: "temp_set", it.toInt()) }
        (patch.opt("mode") as? String)?.let { acModes[it] }?.let { out += command(pick(listOf("mode"), codes, custom.opt("mode")) ?: "mode", it) }
        val fan = (patch.opt("fan") as? String)?.let { acFans[it] }
        val fanCode = pick(listOf("fan_speed_enum", "windspeed"), codes, custom.opt("fan"))
        if (fan != null && fanCode != null) out += command(fanCode, fan)
        patch.bool("sleep")?.let { if ("sleep" in codes) out += command("sleep", it) }
        patch.bool("eco")?.let { if ("eco" in codes) out += command("eco", it) }
        return out
    }

    fun acState(status: List<JSONObject>): JSONObject {
        val out = JSONObject()
        for (dp in status) {
            val value = dp.opt("value") ?: continue
            when (dp.optString("code")) {
                "switch", "power" -> if (value is Boolean) out.put("on", value)
                "temp_set" -> value.asNumber()?.let { out.put("target", it) }
                "temp_current" -> value.asNumber()?.let { out.put("roomTemp", it) }
                "mode" -> (value as? String)?.let { v -> acModes.entries.firstOrNull { it.value == v }?.let { out.put("mode", it.key) } }
                "fan_speed_enum", "windspeed" -> (value as? String)?.let { v -> acFans.entries.firstOrNull { it.value == v }?.let { out.put("fan", it.key) } }
            }
        }
        return out
    }

    /** Reading of a temperature sensor in °C (Tuya reports tenths of a degree). */
    fun sensorTemperature(status: List<JSONObject>): Double? {
        for (dp in status) {
            if (dp.optString("code") !in listOf("temp_current", "va_temperature")) continue
            val v = dp.opt("value").asNumber() ?: continue
            return if (abs(v) > 60) v / 10 else v
        }
        return null
    }

    /** Swift's `as? Double` on a JSON value: numbers only, never numeric strings. */
    private fun Any?.asNumber(): Double? = (this as? Number)?.toDouble()

    private fun hsv(hex: String): Triple<Double, Double, Double>? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6) return null
        val n = s.toIntOrNull(16) ?: return null
        val r = ((n shr 16) and 255) / 255.0
        val g = ((n shr 8) and 255) / 255.0
        val b = (n and 255) / 255.0
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        var h = 0.0
        if (d > 0) {
            h = when (mx) {
                r -> ((g - b) / d) % 6
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h *= 60
            if (h < 0) h += 360
        }
        return Triple(h, if (mx == 0.0) 0.0 else d / mx, mx)
    }

}
