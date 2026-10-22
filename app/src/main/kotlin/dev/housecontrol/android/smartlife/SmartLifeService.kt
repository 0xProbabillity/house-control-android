package dev.housecontrol.android.smartlife

import dev.housecontrol.android.core.Http
import dev.housecontrol.android.core.SecretStore
import dev.housecontrol.android.core.asDouble
import dev.housecontrol.android.core.fetch
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.objects
import dev.housecontrol.android.core.string
import dev.housecontrol.android.core.toJsonText
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the app needs from a device backend, whichever way the account is connected. */
interface DeviceCloud {
    val isConfigured: Boolean
    val knownCodes: Map<String, Set<String>>
    /** Numeric ranges of each device's data points (brightness scales differ per model). */
    val knownRanges: Map<String, Ranges>
    suspend fun listDevices(): List<JSONObject>
    suspend fun status(ids: List<String>): Map<String, List<JSONObject>>
    suspend fun send(commands: List<JSONObject>, deviceId: String)
    /** Makes sure the device's functions are known before the first command is built. */
    suspend fun prepare(deviceId: String) {}
}

/** Tuya's error code behind a failed request ("2008", "1109", …), or "". */
class TuyaFailure(message: String, val code: String = "") : Exception(message)

fun tuyaCode(error: Throwable): String = (error as? TuyaFailure)?.code ?: ""

/**
 * Smart Life account access without a Tuya developer project: the user approves a QR
 * login in the Smart Life app and the app talks to Tuya's "device sharing" gateway.
 * This is a Kotlin port of House Control's Swift client, itself a port of the open-source
 * `tuya-device-sharing-sdk` that the Home Assistant Tuya integration is built on, and it
 * identifies itself the same way.
 */
class SmartLifeService(private val vault: SecretStore, private val client: OkHttpClient = Http.client) : DeviceCloud {
    data class Session(
        val userCode: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Double, // ms since 1970
        val uid: String,
        val endpoint: String,
        val username: String,
    ) {
        fun json() = jsonOf(
            "userCode" to userCode, "accessToken" to accessToken, "refreshToken" to refreshToken,
            "expiresAt" to expiresAt, "uid" to uid, "endpoint" to endpoint, "username" to username,
        )

        companion object {
            fun from(json: JSONObject) = Session(
                json.getString("userCode"), json.getString("accessToken"), json.getString("refreshToken"),
                json.getDouble("expiresAt"), json.optString("uid"), json.getString("endpoint"), json.optString("username"),
            )
        }
    }

    var session: Session? = null
        private set
    private var sessionStamp: Long? = null
    private var loaded = false
    override val knownCodes = HashMap<String, Set<String>>()
    override val knownRanges = HashMap<String, Ranges>()
    private var refreshing = false

    init {
        reloadSession()
    }

    /**
     * Refreshing rotates the refresh token, so a second process (a widget next to the running
     * app) must pick up what the other one stored instead of carrying on with a token that no
     * longer works.
     */
    private fun reloadSession() {
        val stamp = vault.stamp
        if (loaded && stamp == sessionStamp) return
        loaded = true
        sessionStamp = stamp
        session = vault.readText()?.let { runCatching { Session.from(JSONObject(it)) }.getOrNull() }
    }

    override val isConfigured get() = session != null

    val publicInfo: JSONObject
        get() = jsonOf("connected" to isConfigured, "userCode" to (session?.userCode ?: ""), "account" to (session?.username ?: ""))

    private fun store(next: Session?) {
        session = next
        vault.writeText(next?.json()?.toString())
        sessionStamp = vault.stamp
    }

    fun signOut() {
        store(null)
        knownCodes.clear()
        knownRanges.clear()
    }

    // MARK: - QR login

    /** Asks Tuya for a login ticket; the returned string is what the QR code must contain. */
    suspend fun beginLogin(userCode: String): Pair<String, String> {
        val code = userCode.trim()
        if (code.isEmpty()) throw TuyaFailure("Enter the User Code from the Smart Life app first.")
        val url = "$LOGIN_HOST/v1.0/m/life/home-assistant/qrcode/tokens?clientid=$CLIENT_ID&usercode=${Transport.encode(code)}&schema=$SCHEMA"
        val json = json(Http.request(url).post(Http.body("")).build())
        val token = json.optJSONObject("result")?.string("qrcode")
        if (json.optBoolean("success") != true || token == null) throw TuyaFailure(explain(json, "Tuya did not accept this User Code."))
        return token to "tuyaSmart--qrLogin?token=$token"
    }

    /** True once the user has confirmed the login in the Smart Life app. */
    suspend fun finishLogin(token: String, userCode: String): Boolean {
        val code = userCode.trim()
        val url = "$LOGIN_HOST/v1.0/m/life/home-assistant/qrcode/tokens/$token?clientid=$CLIENT_ID&usercode=${Transport.encode(code)}"
        val json = json(Http.request(url).build())
        val r = json.optJSONObject("result") ?: return false
        val access = r.string("access_token")
        val refresh = r.string("refresh_token")
        val endpoint = r.string("endpoint")
        if (json.optBoolean("success") != true || access == null || refresh == null || endpoint == null) return false
        val now = json.opt("t").asDouble() ?: (System.currentTimeMillis().toDouble())
        store(Session(code, access, refresh, now + (r.opt("expire_time").asDouble() ?: 7200.0) * 1000,
            r.optString("uid"), endpoint, r.optString("username")))
        return true
    }

    // MARK: - Devices

    override suspend fun listDevices(): List<JSONObject> {
        val devices = mutableListOf<JSONObject>()
        val homes = (request("GET", "/v1.0/m/life/users/homes") as? JSONArray).objects()
        for (home in homes) {
            // ownerId arrives as a number or a string depending on the account.
            val homeId = when (val owner = home.opt("ownerId")) {
                is String -> owner
                is Number -> JSONObject.numberToString(owner)
                else -> null
            } ?: continue
            val list = (request("GET", "/v1.0/m/life/ha/home/devices", query = jsonOf("homeId" to homeId)) as? JSONArray).objects()
            for (d in list) {
                val id = d.string("id") ?: continue
                val codes = d.objects("status").mapNotNull { it.string("code") }
                knownCodes[id] = codes.toSet()
                devices += jsonOf(
                    "id" to id, "name" to (d.string("name") ?: id), "category" to d.optString("category"),
                    "product" to d.optString("product_name"), "online" to d.optBoolean("online", false),
                    "codes" to codes, "home" to home.optString("name"),
                )
            }
        }
        return devices
    }

    override suspend fun status(ids: List<String>): Map<String, List<JSONObject>> {
        val out = HashMap<String, List<JSONObject>>()
        for (chunk in ids.chunked(20)) {
            val list = (request("GET", "/v1.0/m/life/ha/devices/detail", query = jsonOf("devIds" to chunk.joinToString(","))) as? JSONArray).objects()
            for (d in list) {
                val id = d.string("id") ?: continue
                val status = d.optJSONArray("status") ?: continue
                out[id] = status.objects()
                knownCodes[id] = knownCodes[id].orEmpty() + status.objects().mapNotNull { it.string("code") }
            }
        }
        for (id in ids) if (id !in knownRanges) loadSpecification(id)
        return out
    }

    override suspend fun send(commands: List<JSONObject>, deviceId: String) {
        if (commands.isEmpty()) return
        request("POST", "/v1.1/m/thing/$deviceId/commands", body = jsonOf("commands" to JSONArray(commands)))
    }

    override suspend fun prepare(deviceId: String) {
        if (deviceId !in knownRanges) loadSpecification(deviceId)
    }

    /**
     * Functions and value ranges of a device; fetched once. Some devices (Zigbee bulbs,
     * infrared remotes) report no status at all, so the specification is the only place
     * that says which commands they understand.
     */
    private suspend fun loadSpecification(id: String) {
        knownRanges[id] = emptyMap() // remembered even on failure so it is not retried on every poll
        val spec = runCatching { request("GET", "/v1.1/m/life/$id/specifications") as? JSONObject }.getOrNull() ?: return
        val ranges = HashMap<String, Pair<Double, Double>>()
        val codes = knownCodes[id].orEmpty().toMutableSet()
        for (key in listOf("status", "functions")) {
            for (item in spec.objects(key)) {
                val code = item.string("code") ?: continue
                codes += code
                val raw = item.opt("values") as? String ?: continue
                val values = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val lo = values.opt("min") as? Number ?: continue
                val hi = values.opt("max") as? Number ?: continue
                ranges[code] = lo.toDouble() to hi.toDouble()
            }
        }
        knownCodes[id] = codes
        knownRanges[id] = ranges
    }

    // MARK: - Encrypted, signed transport

    private suspend fun refreshIfNeeded() {
        val s = session ?: return
        if (refreshing || s.expiresAt - 60_000 >= System.currentTimeMillis()) return
        refreshing = true
        try {
            val result = runCatching { request("GET", "/v1.0/m/token/${s.refreshToken}", refresh = false) as? JSONObject }.getOrNull() ?: return
            val access = result.string("accessToken") ?: return
            val refresh = result.string("refreshToken") ?: return
            store(s.copy(
                accessToken = access, refreshToken = refresh,
                expiresAt = System.currentTimeMillis() + (result.opt("expireTime").asDouble() ?: 7200.0) * 1000,
                uid = result.string("uid") ?: s.uid,
            ))
        } finally {
            refreshing = false
        }
    }

    private suspend fun request(method: String, path: String, query: JSONObject = JSONObject(),
                                body: JSONObject = JSONObject(), refresh: Boolean = true): Any {
        reloadSession()
        if (refresh) refreshIfNeeded()
        val s = session ?: throw TuyaFailure("Not signed in to Smart Life")

        val rid = UUID.randomUUID().toString().lowercase()
        val hashKey = Transport.md5Hex(rid + s.refreshToken)
        val secret = Transport.secret(rid, hashKey)

        var queryEnc = ""
        var url = s.endpoint + path
        if (query.length() > 0) {
            queryEnc = Transport.encrypt(query.toJsonText(), secret)
            url += "?encdata=" + Transport.encode(queryEnc, extra = "-._~")
        }
        val bodyEnc = if (body.length() > 0) Transport.encrypt(body.toJsonText(), secret) else ""

        val t = System.currentTimeMillis().toString()
        val headers = listOf("X-appKey" to CLIENT_ID, "X-requestId" to rid, "X-sid" to "", "X-time" to t, "X-token" to s.accessToken)
        val builder = Http.request(url)
        for ((name, value) in headers) builder.header(name, value)
        builder.header("X-sign", Transport.sign(hashKey, headers, queryEnc, bodyEnc))
        if (bodyEnc.isNotEmpty()) builder.method(method, Http.body(jsonOf("encdata" to bodyEnc).toString()))
        else builder.method(method, if (method == "POST") Http.body("") else null)

        val json = json(builder.build())
        if (json.optBoolean("success") != true) throw TuyaFailure(explain(json, "Smart Life request failed"), code(json))
        val cipher = json.opt("result") as? String ?: return json.opt("result") ?: JSONObject.NULL
        val plain = Transport.decrypt(cipher, secret)
        return runCatching { JSONTokener(plain).nextValue() }.getOrDefault(plain)
    }

    private suspend fun json(request: okhttp3.Request): JSONObject {
        val answer = client.fetch(request)
        return runCatching { JSONObject(answer.text) }.getOrElse { throw TuyaFailure("Unexpected response from Tuya") }
    }

    companion object {
        private const val CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
        private const val SCHEMA = "haauthorize"
        private const val LOGIN_HOST = "https://apigw.iotbing.com"

        private fun code(json: JSONObject): String = when (val c = json.opt("code")) {
            is String -> c
            is Number -> JSONObject.numberToString(c)
            else -> ""
        }

        private fun explain(json: JSONObject, fallback: String): String {
            val code = code(json)
            // 2001: the cloud cannot reach the device (unplugged, or only paired over Bluetooth).
            if (code == "2001") return "device is offline — Smart Life cannot reach it over Wi-Fi (code 2001)"
            val msg = json.string("msg") ?: fallback
            return if (code.isEmpty()) msg else "$msg (code $code)"
        }
    }

    /**
     * The gateway's request envelope: per-request AES-GCM key derived from the request id
     * and the refresh token, payloads sent as `encdata`, HMAC over headers + payloads.
     */
    object Transport {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        private val random = SecureRandom()

        fun md5Hex(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).hex()

        fun secret(requestId: String, hashKey: String) = hmac(requestId.toByteArray(), hashKey.toByteArray()).hex().take(16)

        fun sign(hashKey: String, headers: List<Pair<String, String>>, query: String, body: String): String {
            val text = headers.filter { it.second.isNotEmpty() }.joinToString("||") { "${it.first}=${it.second}" } + query + body
            return hmac(hashKey.toByteArray(), text.toByteArray()).hex()
        }

        fun encrypt(plain: String, secret: String, nonce: String? = null): String {
            val nonceText = nonce ?: (1..12).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), GCMParameterSpec(128, nonceText.toByteArray()))
            val sealed = cipher.doFinal(plain.toByteArray())
            val b64 = Base64.getEncoder()
            return b64.encodeToString(nonceText.toByteArray()) + b64.encodeToString(sealed)
        }

        fun decrypt(text: String, secret: String): String {
            val data = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
            if (data == null || data.size <= 28) throw TuyaFailure("Could not decode the response from Smart Life")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret.toByteArray(), "AES"), GCMParameterSpec(128, data, 0, 12))
            return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }

        /** Percent-encodes everything but ASCII letters, digits and `extra`. */
        fun encode(text: String, extra: String = ""): String = buildString {
            for (byte in text.toByteArray()) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c.isLetterOrDigit() || c in extra)) append(c) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
