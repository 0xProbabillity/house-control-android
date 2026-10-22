package dev.housecontrol.android.smartlife

import dev.housecontrol.android.core.Http
import dev.housecontrol.android.core.SecretStore
import dev.housecontrol.android.core.fetch
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.objects
import dev.housecontrol.android.core.string
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal client for the Tuya Cloud OpenAPI ("Smart Home" project with the Smart Life
 * app account linked to it), the editor's "Advanced" alternative to the QR sign-in.
 * Covers exactly what the app needs: list devices, read their status, send commands.
 */
class TuyaService(private val vault: SecretStore, private val client: OkHttpClient = Http.client) : DeviceCloud {
    data class Credentials(val accessId: String, val accessSecret: String, val region: String)

    var credentials: Credentials? = vault.readText()?.let { text ->
        runCatching {
            val json = JSONObject(text)
            Credentials(json.getString("accessId"), json.getString("accessSecret"), json.optString("region", "eu"))
        }.getOrNull()
    }
        private set
    private var token: String? = null
    private var tokenExpiry = 0L
    override val knownCodes = HashMap<String, Set<String>>()
    override val knownRanges = HashMap<String, Ranges>()

    override val isConfigured: Boolean
        get() = credentials?.let { it.accessId.isNotEmpty() && it.accessSecret.isNotEmpty() && it.region in HOSTS } ?: false

    /** What the UI may know: never the secret itself. */
    val publicInfo: JSONObject
        get() = jsonOf(
            "configured" to isConfigured, "accessId" to (credentials?.accessId ?: ""), "region" to (credentials?.region ?: "eu"),
            "hasSecret" to (credentials?.accessSecret?.isNotEmpty() == true),
        )

    fun save(next: Credentials?) {
        credentials = next
        token = null
        tokenExpiry = 0
        vault.writeText(next?.let { jsonOf("accessId" to it.accessId, "accessSecret" to it.accessSecret, "region" to it.region).toString() })
    }

    // MARK: - API

    override suspend fun listDevices(): List<JSONObject> {
        val devices = mutableListOf<JSONObject>()
        var lastKey: String? = null
        do {
            val query = sortedMapOf("size" to "50")
            lastKey?.let { query["last_row_key"] = it }
            val page = request("GET", "/v1.0/iot-01/associated-users/devices", query) as? JSONObject ?: break
            for (d in page.objects("devices")) {
                val id = d.string("id") ?: continue
                val codes = d.objects("status").mapNotNull { it.string("code") }
                knownCodes[id] = codes.toSet()
                devices += jsonOf(
                    "id" to id, "name" to (d.string("name") ?: id), "category" to d.optString("category"),
                    "product" to d.optString("product_name"), "online" to d.optBoolean("online", false), "codes" to codes,
                )
            }
            lastKey = if (page.optBoolean("has_more")) page.string("last_row_key") else null
        } while (lastKey != null)
        return devices
    }

    override suspend fun status(ids: List<String>): Map<String, List<JSONObject>> {
        val out = HashMap<String, List<JSONObject>>()
        for (chunk in ids.chunked(20)) {
            val result = request("GET", "/v1.0/iot-03/devices/status", sortedMapOf("device_ids" to chunk.joinToString(",")))
            for (entry in (result as? JSONArray).objects()) {
                val id = entry.string("id") ?: continue
                val status = entry.optJSONArray("status")?.objects() ?: continue
                out[id] = status
                knownCodes[id] = status.mapNotNull { it.string("code") }.toSet()
            }
        }
        return out
    }

    override suspend fun send(commands: List<JSONObject>, deviceId: String) {
        if (commands.isEmpty()) return
        request("POST", "/v1.0/iot-03/devices/$deviceId/commands", body = jsonOf("commands" to JSONArray(commands)).toString())
    }

    // MARK: - Signing

    private suspend fun accessToken(): String {
        token?.let { if (tokenExpiry > System.currentTimeMillis() + 60_000) return it }
        val result = request("GET", "/v1.0/token", sortedMapOf("grant_type" to "1"), authorized = false) as? JSONObject
        val value = result?.string("access_token") ?: throw TuyaFailure("Tuya did not return an access token")
        token = value
        tokenExpiry = System.currentTimeMillis() + result.optLong("expire_time", 3600) * 1000
        return value
    }

    private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: String? = null,
                                authorized: Boolean = true, retry: Boolean = true): Any {
        val c = credentials
        val host = c?.let { HOSTS[it.region] }
        if (c == null || !isConfigured || host == null) throw TuyaFailure("Tuya credentials are not set")
        val queryString = query.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val signedUrl = if (queryString.isEmpty()) path else "$path?$queryString"

        val bodyHash = MessageDigest.getInstance("SHA-256").digest((body ?: "").toByteArray()).joinToString("") { "%02x".format(it) }
        val t = System.currentTimeMillis().toString()
        val accessToken = if (authorized) accessToken() else ""
        val payload = c.accessId + accessToken + t + "$method\n$bodyHash\n\n$signedUrl"
        val sign = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(c.accessSecret.toByteArray(), "HmacSHA256")) }
            .doFinal(payload.toByteArray()).joinToString("") { "%02X".format(it) }

        val builder = Http.request("https://$host$signedUrl")
            .header("client_id", c.accessId).header("sign", sign).header("t", t).header("sign_method", "HMAC-SHA256")
            .method(method, body?.let(Http::body))
        if (authorized) builder.header("access_token", accessToken)

        val json = runCatching { JSONObject(client.fetch(builder.build()).text) }.getOrElse { throw TuyaFailure("Unexpected response from Tuya") }
        if (json.optBoolean("success")) return json.opt("result") ?: JSONObject.NULL
        val code = json.optInt("code")
        // 1010 / 1011: token expired or invalid. Fetch a fresh one once.
        if (authorized && retry && (code == 1010 || code == 1011)) {
            token = null
            return request(method, path, query, body, authorized = true, retry = false)
        }
        throw TuyaFailure("${json.string("msg") ?: "Tuya request failed"} (code $code)", code.toString())
    }

    companion object {
        val HOSTS = mapOf(
            "eu" to "openapi.tuyaeu.com",        // Central Europe
            "eu-w" to "openapi-weaz.tuyaeu.com", // Western Europe
            "us" to "openapi.tuyaus.com",        // Western America
            "us-e" to "openapi-ueaz.tuyaus.com", // Eastern America
            "cn" to "openapi.tuyacn.com",
            "in" to "openapi.tuyain.com",
            "sg" to "openapi-sg.iotbing.com",
        )
    }
}
