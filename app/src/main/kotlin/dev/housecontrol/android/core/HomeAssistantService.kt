package dev.housecontrol.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A Home Assistant server on the local network (or anywhere it is reachable), through its
 * REST API with a long-lived access token. Lights and switches become lamps, `climate`
 * entities become air conditioners and temperature sensors can stand in for an AC's room
 * reading. A WebSocket subscription tells the hub the moment something changes.
 *
 * Everything that changes the service's own state runs on `scope` (the main thread).
 */
class HomeAssistantService(
    private val vault: SecretStore,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = Http.client,
) : DeviceService {
    override val id = "homeassistant"
    override val title = "Home Assistant"
    override var onChange: (() -> Unit)? = null

    data class Config(val url: String, val token: String, val name: String? = null, val version: String? = null) {
        fun json() = jsonOf("url" to url, "token" to token, "name" to name, "version" to version)

        companion object {
            fun from(json: JSONObject?): Config? {
                val url = json?.string("url") ?: return null
                val token = json.string("token") ?: return null
                return Config(url, token, json.string("name"), json.string("version"))
            }
        }
    }

    private var config: Config? = vault.readText()?.let { runCatching { Config.from(JSONObject(it)) }.getOrNull() }
    private var socket: WebSocket? = null
    private var socketRetry: Job? = null
    /** Each climate entity's own names for the panel's fan speeds and modes, from the last read. */
    private val fanNames = HashMap<String, Map<String, String>>()
    private val modeNames = HashMap<String, Map<String, String>>()
    /** The entities linked in the plan: only their changes are worth a read. */
    @Volatile private var watched: Set<String> = emptySet()
    private var changePending = false

    init {
        if (config != null) listen()
    }

    override val isConnected get() = config != null

    override val info: JSONObject
        get() {
            val c = config ?: return jsonOf("connected" to false)
            return jsonOf("connected" to true, "url" to c.url, "name" to (c.name ?: "Home Assistant"), "version" to (c.version ?: ""))
        }

    /** What a paired Mac receives over the encrypted sync. */
    val shareable: JSONObject?
        get() = config?.let { jsonOf("url" to it.url, "token" to it.token, "name" to (it.name ?: ""), "version" to (it.version ?: "")) }

    // MARK: - Connecting

    /** Checks the address and the token, then keeps them. */
    suspend fun connect(rawUrl: String, rawToken: String) {
        val url = normalize(rawUrl)
        val token = rawToken.trim()
        if (token.isEmpty()) throw ServiceFailure("Paste a long-lived access token (your profile in Home Assistant → Security).")
        val candidate = Config(url, token)
        val about = request("GET", "/api/config", config = candidate) as? JSONObject ?: JSONObject()
        val connected = candidate.copy(name = about.string("location_name"), version = about.string("version"))
        config = connected
        vault.writeText(connected.json().toString())
        listen()
    }

    /** Takes a connection shared by the Mac; null removes it. */
    fun adopt(shared: JSONObject?) {
        val url = shared?.string("url")
        val token = shared?.string("token")
        if (url == null || token == null) {
            if (config != null) disconnect()
            return
        }
        val name = shared.string("name")
        if (config?.url == url && config?.token == token && config?.name == name) return
        val next = Config(url, token, name, shared.string("version"))
        config = next
        vault.writeText(next.json().toString())
        listen()
    }

    override fun watch(ids: Set<String>) {
        watched = ids
    }

    override fun disconnect() {
        config = null
        vault.write(null)
        socketRetry?.cancel()
        socket?.close(1001, null)
        socket = null
    }

    // MARK: - Devices

    override suspend fun devices(): List<ServiceDevice> {
        val states = (request("GET", "/api/states") as? JSONArray).objects()
        val areas = runCatching { areaNames() }.getOrDefault(emptyMap())
        return states.mapNotNull { state ->
            device(state)?.also { it.room = areas[it.id] }
        }.sortedWith(compareBy({ it.kind.raw }, { it.name }))
    }

    /** Area of every entity, in one template request (the REST API has no area endpoint). */
    private suspend fun areaNames(): Map<String, String> {
        val template = "{% for e in states %}{{ e.entity_id }}\t{{ area_name(e.entity_id) or '' }}\n{% endfor %}"
        val text = request("POST", "/api/template", body = jsonOf("template" to template), raw = true) as? String ?: return emptyMap()
        val out = HashMap<String, String>()
        for (line in text.split("\n")) {
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2 && parts[1].isNotEmpty() && parts[1] != "None") out[parts[0]] = parts[1]
        }
        return out
    }

    override suspend fun read(ids: Set<String>): Map<String, DeviceReading> {
        if (ids.isEmpty()) return emptyMap()
        val states = (request("GET", "/api/states") as? JSONArray).objects()
        val out = HashMap<String, DeviceReading>()
        for (state in states) {
            val id = state.string("entity_id") ?: continue
            if (id !in ids) continue
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            if (id.startsWith("climate.")) {
                fanNames[id] = FanNames.table(attributes.optJSONArray("fan_modes").strings())
                modeNames[id] = modeTable(attributes.optJSONArray("hvac_modes").strings())
            }
            out[id] = reading(state)
        }
        return out
    }

    override suspend fun send(kind: DeviceKind, id: String, patch: JSONObject, state: JSONObject) {
        for (call in calls(id, patch, state, fanNames[id].orEmpty(), modeNames[id].orEmpty())) {
            request("POST", "/api/services/${call.domain}/${call.service}", body = call.data)
        }
    }

    data class Call(val domain: String, val service: String, val data: JSONObject)

    companion object {
        /** "homeassistant.local:8123" → "http://homeassistant.local:8123". */
        fun normalize(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) throw ServiceFailure("Enter the address of your Home Assistant, for example http://homeassistant.local:8123")
            if (!s.contains("://")) s = "http://$s"
            while (s.endsWith("/")) s = s.dropLast(1)
            val url = s.toHttpUrlOrNull()
            val scheme = s.substringBefore("://").lowercase()
            if (url == null || scheme !in listOf("http", "https")) throw ServiceFailure("“$raw” is not a web address")
            val explicitPort = Regex("^[a-zA-Z]+://(\\[[^]]+]|[^/:]+):\\d+").containsMatchIn(s)
            if (!explicitPort && scheme == "http" && (url.host.endsWith(".local") || url.host == "homeassistant")) {
                s += ":8123" // Home Assistant's default port, which people leave out
            }
            return s
        }

        fun device(state: JSONObject): ServiceDevice? {
            val id = state.string("entity_id") ?: return null
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val name = attributes.string("friendly_name") ?: id
            val device = when (id.substringBefore(".")) {
                "light" -> {
                    val modes = attributes.optJSONArray("supported_color_modes").strings()
                    ServiceDevice(id, name, DeviceKind.LIGHT).apply {
                        capabilities = lightCapabilities(modes)
                        detail = "light · " + if (modes.isEmpty()) "on/off" else modes.joinToString(", ")
                    }
                }
                "switch" -> ServiceDevice(id, name, DeviceKind.LIGHT, detail = "switch · on/off")
                "climate" -> ServiceDevice(id, name, DeviceKind.AC).apply {
                    val lo = attributes.double("min_temp")
                    val hi = attributes.double("max_temp")
                    if (lo != null && hi != null) range = maxOf(lo, 10.0) to minOf(hi, 32.0)
                    detail = "climate · " + attributes.optJSONArray("hvac_modes").strings().joinToString(", ")
                }
                "sensor" -> {
                    if (!isTemperature(attributes)) return null
                    ServiceDevice(id, name, DeviceKind.SENSOR, detail = "temperature sensor")
                }
                else -> return null
            }
            device.online = state.optString("state") !in listOf("unavailable", "unknown")
            return device
        }

        fun lightCapabilities(modes: List<String>): List<String> {
            val caps = mutableListOf<String>()
            if (modes.any { it != "onoff" }) caps += "brightness"
            if ("color_temp" in modes) caps += "temp"
            if (modes.any { it in listOf("hs", "xy", "rgb", "rgbw", "rgbww") }) caps += "color"
            return caps
        }

        fun isTemperature(attributes: JSONObject) =
            attributes.optString("device_class") == "temperature" || attributes.optString("unit_of_measurement") in listOf("°C", "°F")

        /** The panel's AC modes and the climate entity's names for them. */
        fun modeTable(hvacModes: List<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((panel, names) in listOf(
                "cool" to listOf("cool"), "heat" to listOf("heat"), "dry" to listOf("dry"),
                "fan" to listOf("fan_only"), "auto" to listOf("auto", "heat_cool"),
            )) {
                names.firstOrNull { it in hvacModes }?.let { out[panel] = it }
            }
            return out
        }

        fun reading(state: JSONObject): DeviceReading {
            val id = state.optString("entity_id")
            val value = state.optString("state")
            val attributes = state.optJSONObject("attributes") ?: JSONObject()
            val reading = DeviceReading(online = value !in listOf("unavailable", "unknown"))
            if (!reading.online) return reading

            if (id.startsWith("light.") || id.startsWith("switch.")) {
                val light = jsonOf("on" to (value == "on"))
                if (value == "on") attributes.double("brightness")?.let { light.put("brightness", (it / 255).coerceIn(0.05, 1.0)) }
                when (attributes.optString("color_mode")) {
                    "color_temp" -> attributes.double("color_temp_kelvin")?.let {
                        light.put("temp", it.coerceIn(2200.0, 6500.0))
                        light.put("color", JSONObject.NULL)
                    }
                    "hs", "xy", "rgb", "rgbw", "rgbww" -> {
                        val rgb = attributes.optJSONArray("rgb_color")
                        if (rgb != null && rgb.length() == 3) {
                            val c = (0 until 3).mapNotNull { rgb.opt(it).asDouble() }
                            if (c.size == 3) light.put("color", ColorMath.hex(c[0] / 255, c[1] / 255, c[2] / 255))
                        }
                    }
                }
                reading.light = light
            } else if (id.startsWith("climate.")) {
                val modes = modeTable(attributes.optJSONArray("hvac_modes").strings())
                val ac = jsonOf("on" to (value != "off"))
                // On, but not running right now: a thermostat whose room is already warm (or cool) enough.
                val action = attributes.optString("hvac_action")
                ac.put("idle", value != "off" && (action == "idle" || action == "off"))
                modes.entries.firstOrNull { it.value == value }?.let { ac.put("mode", it.key) }
                val t = attributes.double("temperature")
                val lo = attributes.double("target_temp_low")
                val hi = attributes.double("target_temp_high")
                if (t != null) ac.put("target", t) else if (lo != null && hi != null) ac.put("target", ((lo + hi) / 2).roundHalfAway())
                attributes.double("current_temperature")?.let { ac.put("roomTemp", it) }
                val fans = FanNames.table(attributes.optJSONArray("fan_modes").strings())
                attributes.string("fan_mode")?.let { current ->
                    val fan = FanNames.panel(current)
                    if (fan != null && fan in fans) ac.put("fan", fan)
                }
                val swings = attributes.optJSONArray("swing_modes").strings()
                val swing = attributes.string("swing_mode")
                if (swing != null && swings.isNotEmpty()) ac.put("swing", if (swing == "off") "off" else "swing")
                val presets = attributes.optJSONArray("preset_modes").strings()
                val preset = attributes.optString("preset_mode")
                if ("sleep" in presets) ac.put("sleep", preset == "sleep")
                if ("eco" in presets) ac.put("eco", preset == "eco")
                reading.ac = ac
                reading.features = jsonOf(
                    "modes" to listOf("cool", "heat", "dry", "fan", "auto").filter { it in modes },
                    "fans" to FanNames.order.filter { it in fans },
                    "swing" to ("off" in swings && swings.size > 1),
                    "sleep" to ("sleep" in presets), "eco" to ("eco" in presets),
                )
            } else if (id.startsWith("sensor.")) {
                value.toDoubleOrNull()?.let { t ->
                    reading.temperature = if (attributes.optString("unit_of_measurement") == "°F") (t - 32) * 5 / 9 else t
                }
            }
            return reading
        }

        fun calls(id: String, patch: JSONObject, state: JSONObject, fans: Map<String, String>, modes: Map<String, String>): List<Call> {
            val domain = id.substringBefore(".")
            fun call(service: String, data: JSONObject = JSONObject()) =
                Call(domain, service, jsonOf("entity_id" to id).mergeFrom(data))

            if (domain == "switch") {
                val on = patch.bool("on") ?: return emptyList()
                return listOf(call(if (on) "turn_on" else "turn_off"))
            }
            if (domain == "light") {
                if (patch.bool("on") == false) return listOf(call("turn_off"))
                val data = JSONObject()
                patch.double("brightness")?.let { data.put("brightness_pct", (it * 100).roundHalfAway().toInt()) }
                patch.double("temp")?.let { data.put("color_temp_kelvin", it.toInt()) }
                (patch.opt("color") as? String)?.let(ColorMath::rgb)?.let { (r, g, b) ->
                    data.put("rgb_color", jsonArrayOf((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt()))
                }
                if (data.length() == 0 && patch.bool("on") != true) return emptyList()
                return listOf(call("turn_on", data))
            }
            if (domain != "climate") return emptyList()

            val out = mutableListOf<Call>()
            val on = patch.bool("on")
            if (on == false) return listOf(call("set_hvac_mode", jsonOf("hvac_mode" to "off")))
            // Picking a mode is what switches a climate entity on.
            if (on == true || patch.has("mode")) {
                val mode = (patch.opt("mode") as? String) ?: (state.opt("mode") as? String) ?: "cool"
                (modes[mode] ?: modes.values.firstOrNull())?.let { out += call("set_hvac_mode", jsonOf("hvac_mode" to it)) }
            }
            patch.double("target")?.let { out += call("set_temperature", jsonOf("temperature" to it)) }
            (patch.opt("fan") as? String)?.let { fans[it] }?.let { out += call("set_fan_mode", jsonOf("fan_mode" to it)) }
            (patch.opt("swing") as? String)?.let {
                out += call("set_swing_mode", jsonOf("swing_mode" to if (it == "off") "off" else "on"))
            }
            for (preset in listOf("sleep", "eco")) {
                patch.bool(preset)?.let { out += call("set_preset_mode", jsonOf("preset_mode" to if (it) preset else "none")) }
            }
            return out
        }
    }

    // MARK: - HTTP

    private suspend fun request(method: String, path: String, body: JSONObject? = null, raw: Boolean = false,
                                config: Config? = null): Any? {
        val c = config ?: this.config ?: throw ServiceFailure("Connect Home Assistant first")
        val builder = Http.request(c.url + path).header("Authorization", "Bearer ${c.token}")
        if (body != null) builder.method(method, Http.body(body.toJsonText())) else builder.method(method, null)
        val answer = try {
            client.fetch(builder.build())
        } catch (e: Exception) {
            throw ServiceFailure("Home Assistant at ${c.url} did not answer: ${e.readable}")
        }
        when (answer.code) {
            in 200..299 -> {}
            401, 403 -> throw ServiceFailure("Home Assistant refused the token. Create a new long-lived access token and connect again.")
            404 -> throw ServiceFailure("Home Assistant does not know $path. Is the API enabled?")
            else -> throw ServiceFailure("Home Assistant answered ${answer.code} ${answer.text.take(200)}")
        }
        if (raw) return answer.text
        val text = answer.text.trim()
        if (text.isEmpty()) return null
        return runCatching { if (text.startsWith("[")) JSONArray(text) else JSONObject(text) }.getOrNull()
    }

    // MARK: - Live updates

    /**
     * Subscribes to state changes of the entities the hub cares about, and says so through
     * `onChange`. Reconnects by itself; polling covers the gaps.
     */
    private fun listen() {
        socketRetry?.cancel()
        socket?.close(1001, null)
        socket = null
        val c = config ?: return
        val url = c.url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api/websocket"
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return
        val streaming = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()
        lateinit var opened: WebSocket
        opened = streaming.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (message.optString("type")) {
                    "auth_required" -> webSocket.send(jsonOf("type" to "auth", "access_token" to c.token).toString())
                    "auth_ok" -> webSocket.send(jsonOf("id" to 1, "type" to "subscribe_events", "event_type" to "state_changed").toString())
                    "event" -> {
                        val entity = message.optJSONObject("event")?.optJSONObject("data")?.optString("entity_id") ?: return
                        if (entity in watched) scope.launch { changed(webSocket) }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { retry(webSocket) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { retry(webSocket) }
            }
        })
        socket = opened
    }

    private fun changed(from: WebSocket) {
        if (from !== socket || changePending) return
        // A burst of changes (a scene) becomes one read.
        changePending = true
        scope.launch {
            delay(400)
            changePending = false
            onChange?.invoke()
        }
    }

    private fun retry(from: WebSocket) {
        if (from !== socket || config == null) return
        socket = null
        socketRetry?.cancel()
        socketRetry = scope.launch {
            delay(15_000)
            listen()
        }
    }
}
