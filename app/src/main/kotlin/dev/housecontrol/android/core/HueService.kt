package dev.housecontrol.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * A Philips Hue Bridge on the local network, through its own API (CLIP v2). No cloud and no
 * account: the bridge hands out a key when its round button is pressed. Hue lamps become the
 * plan's lamps; the temperature reported by Hue motion sensors can stand in for an AC's room
 * reading. The bridge's event stream tells the hub at once when a lamp changes elsewhere.
 */
class HueService(
    private val vault: SecretStore,
    private val scope: CoroutineScope,
    private val deviceType: String = "house_control#Android",
) : DeviceService {
    override val id = "hue"
    override val title = "Philips Hue"
    override var onChange: (() -> Unit)? = null

    /**
     * `certificate` is the SHA-256 of the bridge's certificate, remembered when it was paired:
     * the bridge's certificate is self-signed, so it is trusted on first use and checked ever after.
     */
    data class Config(
        val host: String, val key: String, val bridgeId: String? = null, val name: String? = null, val certificate: String? = null,
    ) {
        fun json() = jsonOf("host" to host, "key" to key).apply {
            bridgeId?.let { put("bridgeId", it) }
            name?.let { put("name", it) }
            certificate?.let { put("certificate", it) }
        }

        companion object {
            fun from(json: JSONObject?): Config? {
                val host = json?.string("host") ?: return null
                val key = json.string("key") ?: return null
                return Config(host, key, json.string("bridgeId"), json.string("name"), json.string("certificate"))
            }
        }
    }

    class ButtonNotPressed : ServiceFailure("Press the round button on the Hue Bridge.")

    private var config: Config? = vault.readText()?.let { runCatching { Config.from(JSONObject(it)) }.getOrNull() }
    private var stream: Job? = null
    @Volatile private var watched: Set<String> = emptySet()

    init {
        if (config != null) listen()
    }

    override val isConnected get() = config != null

    override val info: JSONObject
        get() {
            val c = config ?: return jsonOf("connected" to false)
            return jsonOf("connected" to true, "host" to c.host, "name" to (c.name ?: "Hue Bridge"), "bridgeId" to (c.bridgeId ?: ""))
        }

    val shareable: JSONObject? get() = config?.json()

    fun adopt(shared: JSONObject?) {
        val incoming = Config.from(shared)
        if (incoming == null) {
            if (config != null) disconnect()
            return
        }
        if (incoming.host == config?.host && incoming.key == config?.key) return
        config = incoming
        vault.writeText(incoming.json().toString())
        listen()
    }

    override fun disconnect() {
        config = null
        vault.write(null)
        stream?.cancel()
        stream = null
    }

    override fun watch(ids: Set<String>) {
        watched = ids
    }

    // MARK: - Pairing

    /**
     * Asks the bridge at `host` for a key. Throws [ButtonNotPressed] until someone presses
     * the round button on the bridge (it then accepts pairing for 30 seconds).
     */
    suspend fun pair(rawHost: String, bridgeId: String? = null, name: String? = null) {
        val host = rawHost.trim().removePrefix("https://").removePrefix("http://").trim('/')
        if (host.isEmpty()) throw ServiceFailure("Enter the bridge’s address")
        val trust = BridgeTrust(null) // trust on first use, remember below
        val body = jsonOf("devicetype" to deviceType, "generateclientkey" to true)
        val answer = try {
            trust.client().fetch(Http.request("https://$host/api").post(Http.body(body.toString())).build())
        } catch (e: Exception) {
            throw ServiceFailure("No Hue Bridge answered at $host: ${e.readable}")
        }
        val first = runCatching { JSONArray(answer.text).optJSONObject(0) }.getOrNull() ?: JSONObject()
        first.optJSONObject("error")?.let { error ->
            if (error.optInt("type") == 101) throw ButtonNotPressed()
            throw ServiceFailure("The bridge said: ${error.optString("description", "error")}")
        }
        val key = first.optJSONObject("success")?.string("username")
            ?: throw ServiceFailure("The bridge at $host did not answer like a Hue Bridge")
        var paired = Config(host, key, bridgeId, name, trust.seen)
        config = paired
        // Fill in the bridge's own name for the editor.
        runCatching {
            val bridge = get("/clip/v2/resource/bridge").firstOrNull()
            if (bridge != null) {
                val device = get("/clip/v2/resource/device").firstOrNull { d ->
                    d.objects("services").any { it.optString("rid") == bridge.optString("id") }
                }
                paired = paired.copy(
                    bridgeId = bridge.string("bridge_id") ?: bridgeId,
                    name = device?.optJSONObject("metadata")?.string("name") ?: name,
                )
            }
        }
        config = paired
        vault.writeText(paired.json().toString())
        listen()
    }

    // MARK: - Devices

    override suspend fun devices(): List<ServiceDevice> = coroutineScope {
        val lights = async { get("/clip/v2/resource/light") }
        val rooms = async { get("/clip/v2/resource/room") }
        val sensors = async { get("/clip/v2/resource/temperature") }
        val hardware = async { get("/clip/v2/resource/device") }
        val links = async { get("/clip/v2/resource/zigbee_connectivity") }

        val roomOf = roomIndex(rooms.await())
        val deviceName = HashMap<String, String>()
        for (d in hardware.await()) {
            val id = d.string("id") ?: continue
            val name = d.optJSONObject("metadata")?.string("name") ?: continue
            deviceName.putIfAbsent(id, name)
        }
        val offline = links.await().mapNotNull { l ->
            if (l.optString("status") == "connected") null else l.optJSONObject("owner")?.string("rid")
        }.toSet()

        val out = mutableListOf<ServiceDevice>()
        for (light in lights.await()) {
            val device = device(light) ?: continue
            val owner = light.optJSONObject("owner")?.optString("rid") ?: ""
            device.room = roomOf[owner]
            device.online = owner !in offline
            out += device
        }
        for (sensor in sensors.await()) {
            val id = sensor.string("id") ?: continue
            val owner = sensor.optJSONObject("owner")?.optString("rid") ?: ""
            out += ServiceDevice(id, deviceName[owner] ?: "Hue sensor", DeviceKind.SENSOR, roomOf[owner],
                online = owner !in offline, detail = "temperature sensor")
        }
        out.sortedWith(compareBy({ it.kind.raw }, { it.name }))
    }

    override suspend fun read(ids: Set<String>): Map<String, DeviceReading> = coroutineScope {
        if (ids.isEmpty()) return@coroutineScope emptyMap()
        val lights = async { get("/clip/v2/resource/light") }
        val sensors = async { get("/clip/v2/resource/temperature") }
        val out = HashMap<String, DeviceReading>()
        for (light in lights.await()) {
            val id = light.string("id") ?: continue
            if (id in ids) out[id] = DeviceReading(light = lightState(light))
        }
        for (sensor in sensors.await()) {
            val id = sensor.string("id") ?: continue
            if (id in ids) out[id] = DeviceReading(temperature = temperature(sensor))
        }
        out
    }

    override suspend fun send(kind: DeviceKind, id: String, patch: JSONObject, state: JSONObject) {
        if (kind != DeviceKind.LIGHT) throw ServiceFailure("Hue has no air conditioners")
        val body = lightBody(DeviceKind.withPower(patch, state))
        if (body.length() == 0) return
        val answer = call("PUT", "/clip/v2/resource/light/$id", body)
        answer.optJSONArray("errors").objects().firstOrNull()?.let {
            throw ServiceFailure("Hue: ${it.string("description") ?: "the bridge refused the change"}")
        }
    }

    companion object {
        fun roomIndex(rooms: List<JSONObject>): Map<String, String> {
            val out = HashMap<String, String>()
            for (room in rooms) {
                val name = room.optJSONObject("metadata")?.string("name") ?: continue
                for (child in room.objects("children")) child.string("rid")?.let { out[it] = name }
            }
            return out
        }

        fun device(light: JSONObject): ServiceDevice? {
            val id = light.string("id") ?: return null
            val metadata = light.optJSONObject("metadata")
            val caps = mutableListOf<String>()
            if (light.has("dimming")) caps += "brightness"
            if (light.has("color_temperature")) caps += "temp"
            if (light.has("color")) caps += "color"
            val archetype = metadata?.string("archetype") ?: "light"
            return ServiceDevice(id, metadata?.string("name") ?: "Hue light", DeviceKind.LIGHT, capabilities = caps,
                detail = "${archetype.replace("_", " ")} · " + if (caps.isEmpty()) "on/off" else caps.joinToString(", "))
        }

        fun lightState(light: JSONObject): JSONObject {
            val out = JSONObject()
            light.optJSONObject("on")?.bool("on")?.let { out.put("on", it) }
            light.optJSONObject("dimming")?.double("brightness")?.let { out.put("brightness", (it / 100).coerceIn(0.05, 1.0)) }
            val ct = light.optJSONObject("color_temperature")
            val mirek = ct?.double("mirek")
            val xy = light.optJSONObject("color")?.optJSONObject("xy")
            if (ct?.bool("mirek_valid") == true && mirek != null) {
                out.put("temp", ColorMath.kelvin(mirek).coerceIn(2200.0, 6500.0))
                out.put("color", JSONObject.NULL)
            } else if (xy != null) {
                val x = xy.double("x")
                val y = xy.double("y")
                if (x != null && y != null) out.put("color", ColorMath.fromXY(x, y))
            }
            return out
        }

        fun temperature(sensor: JSONObject): Double? {
            val t = sensor.optJSONObject("temperature")
            return t?.optJSONObject("temperature_report")?.double("temperature") ?: t?.double("temperature")
        }

        fun lightBody(patch: JSONObject): JSONObject {
            val body = JSONObject()
            patch.bool("on")?.let { body.put("on", jsonOf("on" to it)) }
            patch.double("brightness")?.let { body.put("dimming", jsonOf("brightness" to (it * 1000).roundHalfAway() / 10)) }
            patch.double("temp")?.let { body.put("color_temperature", jsonOf("mirek" to ColorMath.mired(it).roundHalfAway().toInt())) }
            (patch.opt("color") as? String)?.let(ColorMath::xy)?.let { (x, y) ->
                body.put("color", jsonOf("xy" to jsonOf("x" to x, "y" to y)))
            }
            return body
        }
    }

    // MARK: - HTTP

    private suspend fun get(path: String): List<JSONObject> = call("GET", path).optJSONArray("data").objects()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val c = config ?: throw ServiceFailure("Pair a Hue Bridge first")
        val request = Http.request("https://${c.host}$path").header("hue-application-key", c.key)
            .method(method, body?.let { Http.body(it.toString()) }).build()
        val answer = try {
            BridgeTrust(c.certificate).client().fetch(request)
        } catch (e: Exception) {
            throw ServiceFailure("The Hue Bridge at ${c.host} did not answer: ${e.readable}")
        }
        if (answer.code == 401 || answer.code == 403) throw ServiceFailure("The Hue Bridge no longer accepts this phone’s key. Pair it again.")
        val json = runCatching { JSONObject(answer.text) }.getOrDefault(JSONObject())
        if (answer.code !in 200..299) {
            val reason = json.optJSONArray("errors").objects().firstOrNull()?.string("description") ?: "status ${answer.code}"
            throw ServiceFailure("Hue: $reason")
        }
        return json
    }

    // MARK: - Event stream

    private fun listen() {
        stream?.cancel()
        val c = config ?: return
        stream = scope.launch {
            val client = BridgeTrust(c.certificate).client(streaming = true)
            while (isActive) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val request = Http.request("https://${c.host}/eventstream/clip/v2")
                            .header("hue-application-key", c.key).header("Accept", "text/event-stream").build()
                        client.newCall(request).execute().use { response ->
                            val source = response.body?.source() ?: return@use
                            while (isActive) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data:") && watched.any { line.contains(it) }) {
                                    scope.launch { onChange?.invoke() }
                                }
                            }
                        }
                    }
                }
                delay(10_000) // the bridge restarted or the Wi-Fi dropped
            }
        }
    }
}

/**
 * Trusts a Hue Bridge's self-signed certificate the first time (`pinned` null: pairing now,
 * the certificate seen ends up in `seen`), and only that certificate afterwards.
 */
class BridgeTrust(private val pinned: String?) : X509TrustManager {
    @Volatile var seen: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        seen = fingerprint
        if (pinned != null && pinned != fingerprint) throw CertificateException("This is not the Hue Bridge that was paired.")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun client(streaming: Boolean = false): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@BridgeTrust), null) }
        return Http.client.newBuilder()
            .sslSocketFactory(context.socketFactory, this)
            // The certificate names the bridge's id, not the address it is reached at; the pin is the check.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(if (streaming) 0 else 8, TimeUnit.SECONDS)
            .build()
    }
}
