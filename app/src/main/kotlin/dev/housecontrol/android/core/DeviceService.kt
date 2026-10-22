package dev.housecontrol.android.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A smart home platform the plan's lamps and ACs can be linked to, besides Smart Life:
 * Home Assistant and Philips Hue. Each one speaks the panel's own vocabulary — a light is
 * `on` / `brightness` 0…1 / `temp` in kelvin / `color` "#rrggbb", an AC is `on` / `target` /
 * `mode` / `fan` / … — so the hub and the UI never see what is underneath.
 */
interface DeviceService {
    /** "homeassistant", "hue": what a plan's `link.service` says. */
    val id: String
    val title: String
    val isConnected: Boolean
    /** What the editor may show about the connection. Never a token or key. */
    val info: JSONObject
    /**
     * Called when the platform says something changed (a lamp switched from its own app),
     * so the hub can read the linked devices again without waiting for the next poll.
     */
    var onChange: (() -> Unit)?

    /** Every device the platform offers. */
    suspend fun devices(): List<ServiceDevice>
    /** The current state of the given devices. Devices the platform does not know are left out. */
    suspend fun read(ids: Set<String>): Map<String, DeviceReading>
    /**
     * Applies a change made in the panel. `state` is the whole new state, for platforms
     * that need more than the changed fields (a colour needs the brightness too).
     */
    suspend fun send(kind: DeviceKind, id: String, patch: JSONObject, state: JSONObject)
    /** The devices linked in the plan, whose changes should reach `onChange`. */
    fun watch(ids: Set<String>) {}
    /** Forgets the connection and its credentials. */
    fun disconnect()
}

enum class DeviceKind(val raw: String) {
    LIGHT("light"), AC("ac"), SENSOR("sensor");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }

        /**
         * A lamp the panel shows as on, given a new brightness or colour, is switched on as well:
         * the real one may be off (switched off elsewhere since the last read), and some
         * platforms ignore a colour sent to a lamp that is off.
         */
        fun withPower(patch: JSONObject, state: JSONObject): JSONObject {
            if (patch.has("on") || state.bool("on") != true ||
                listOf("brightness", "temp", "color").none { patch.has(it) }) return patch
            return patch.copy().put("on", true)
        }
    }
}

/** One device as the editor lists it. */
data class ServiceDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    var room: String? = null,
    var online: Boolean = true,
    /** For lights: which of "brightness", "temp", "color" it supports. */
    var capabilities: List<String> = emptyList(),
    /** For ACs: the set-point range it accepts. */
    var range: Pair<Double, Double>? = null,
    /** A short technical description ("light · color_temp, hs"). */
    var detail: String = "",
) {
    fun json(service: String): JSONObject = jsonOf(
        "service" to service, "id" to id, "name" to name, "kind" to kind.raw,
        "online" to online, "capabilities" to capabilities, "detail" to detail,
    ).apply {
        room?.let { put("room", it) }
        range?.let { put("min", it.first); put("max", it.second) }
    }
}

/** What one device reports, in the panel's terms. */
data class DeviceReading(
    var light: JSONObject? = null,
    var ac: JSONObject? = null,
    /** What an AC can do: `modes`, `fans`, `swing`, `sleep`, `eco` (see the store's acFeatures). */
    var features: JSONObject? = null,
    /** A temperature sensor's reading in °C. */
    var temperature: Double? = null,
    var online: Boolean = true,
)

/** A failure with a message meant for the person using the app. */
open class ServiceFailure(message: String) : Exception(message)

// MARK: - Colour

/** Conversions between the panel's "#rrggbb" and what the platforms take. */
object ColorMath {
    fun rgb(hex: String): Triple<Double, Double, Double>? {
        val s = hex.trim().removePrefix("#")
        if (s.length != 6) return null
        val v = s.toIntOrNull(16) ?: return null
        return Triple(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
    }

    fun hex(r: Double, g: Double, b: Double): String {
        fun byte(v: Double) = (max(0.0, min(1.0, v)) * 255).roundToInt()
        return "#%02x%02x%02x".format(byte(r), byte(g), byte(b))
    }

    /** Hue in degrees 0…360, saturation and value 0…1. */
    fun hsv(hex: String): Triple<Double, Double, Double>? {
        val (r, g, b) = rgb(hex) ?: return null
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val d = hi - lo
        var h = 0.0
        if (d > 0) {
            h = when (hi) {
                r -> 60 * (((g - b) / d) % 6)
                g -> 60 * ((b - r) / d + 2)
                else -> 60 * ((r - g) / d + 4)
            }
        }
        if (h < 0) h += 360
        return Triple(h, if (hi == 0.0) 0.0 else d / hi, hi)
    }

    /** The colour of a hue and saturation at full value, as the panel shows it. */
    fun fromHue(hue: Double, saturation: Double): String {
        val h = ((hue % 360) + 360) % 360 / 60
        val s = max(0.0, min(1.0, saturation))
        val c = s
        val x = c * (1 - abs(h % 2 - 1))
        val m = 1 - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return hex(r + m, g + m, b + m)
    }

    /** CIE 1931 xy of an sRGB colour (Philips' "wide gamut" conversion). */
    fun xy(hex: String): Pair<Double, Double>? {
        val (r, g, b) = rgb(hex) ?: return null
        fun linear(c: Double) = if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
        val (lr, lg, lb) = Triple(linear(r), linear(g), linear(b))
        val x = lr * 0.4124 + lg * 0.3576 + lb * 0.1805
        val y = lr * 0.2126 + lg * 0.7152 + lb * 0.0722
        val z = lr * 0.0193 + lg * 0.1192 + lb * 0.9505
        val sum = x + y + z
        if (sum <= 0) return 0.3127 to 0.3290 // black: the white point
        return (x / sum * 10000).roundHalfAway() / 10000 to (y / sum * 10000).roundHalfAway() / 10000
    }

    /** The sRGB colour of an xy chromaticity at full brightness. */
    fun fromXY(x: Double, y: Double): String {
        if (y <= 0) return "#ffffff"
        val bigY = 1.0
        val bigX = bigY / y * x
        val bigZ = bigY / y * (1 - x - y)
        var r = bigX * 3.2406 - bigY * 1.5372 - bigZ * 0.4986
        var g = -bigX * 0.9689 + bigY * 1.8758 + bigZ * 0.0415
        var b = bigX * 0.0557 - bigY * 0.2040 + bigZ * 1.0570
        val top = maxOf(r, g, b, 1e-9)
        r = max(0.0, r / top); g = max(0.0, g / top); b = max(0.0, b / top)
        fun gamma(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return hex(gamma(r), gamma(g), gamma(b))
    }

    /** Kelvin ↔ mired (what Hue uses for white temperature). */
    fun mired(kelvin: Double) = 1_000_000 / max(1000.0, kelvin)
    fun kelvin(mired: Double) = 1_000_000 / max(1.0, mired)
}

/** Swift's `.rounded()`: halves away from zero (Kotlin's roundToInt rounds them up). */
fun Double.roundHalfAway(): Double = if (this < 0) -Math.round(-this).toDouble() else Math.round(this).toDouble()

// MARK: - Air conditioner vocabulary

/** Maps whatever a platform calls its fan speeds onto the panel's five. */
object FanNames {
    fun panel(name: String): String? {
        val n = name.lowercase()
        return when {
            listOf("turbo", "max", "strong", "highest", "powerful", "boost", "very high").any { n.contains(it) } -> "turbo"
            n.contains("high") -> "high"
            n.contains("med") || n.contains("mid") -> "medium"
            n.contains("low") || n.contains("quiet") || n.contains("silent") -> "low"
            n.contains("auto") -> "auto"
            else -> null
        }
    }

    /** For each panel fan speed, the platform's own name for it (the first one that maps). */
    fun table(names: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in names) {
            if (name.lowercase() == "off") continue
            val p = panel(name) ?: continue
            if (p !in out) out[p] = name
        }
        return out
    }

    val order = listOf("auto", "low", "medium", "high", "turbo")
}

internal fun JSONArray.toStringList(): List<String> = strings()
