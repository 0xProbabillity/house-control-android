package dev.housecontrol.android.smartlife

import dev.housecontrol.android.core.EventLog
import dev.housecontrol.android.core.SecretStore
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.mergeFrom
import dev.housecontrol.android.core.string
import dev.housecontrol.android.core.strings
import org.json.JSONObject

/** The parts of a change that did not reach the device; the panel rolls back only those. */
class CommandRejection(message: String, val fields: List<String>) : Exception(message)

/** A lamp or AC of the plan linked to Smart Life: its kind ("light" / "ac") and its `tuya` link. */
data class CloudLink(val kind: String, val link: JSONObject)

/**
 * Smart Life for the device hub: the QR-linked account when there is one, otherwise a
 * Tuya Cloud project ("Advanced").
 */
class SmartLifePlatform(smartLifeVault: SecretStore, tuyaVault: SecretStore) {
    private val tuya = TuyaService(tuyaVault)
    private val smartLife = SmartLifeService(smartLifeVault)
    private val infrared = InfraredRemote()

    /**
     * The backend real devices are reached through: the QR-linked Smart Life account
     * when there is one, otherwise a Tuya Cloud project, otherwise nothing (simulation).
     */
    private val active: DeviceCloud?
        get() = if (smartLife.isConfigured) smartLife else if (tuya.isConfigured) tuya else null

    val isConnected get() = active != null

    val info: JSONObject get() = smartLife.publicInfo.mergeFrom(jsonOf("connected" to isConnected))

    // MARK: - Commands and state

    /**
     * Sends the panel's change (`patch`; `state` is the device with it applied) to the device
     * a plan's `tuya` link names. Throws [CommandRejection] when part of it did not reach the device.
     */
    suspend fun command(appId: String, kind: String, link: JSONObject, patch: JSONObject, state: JSONObject, log: EventLog) {
        val cloud = active ?: return
        val deviceId = link.string("deviceId") ?: return
        // The first command to a device may arrive before anything is known about it.
        cloud.prepare(deviceId)
        val codes = cloud.knownCodes[deviceId].orEmpty()
        val send: suspend (List<JSONObject>) -> Unit = { commands ->
            if (commands.isNotEmpty()) {
                try {
                    cloud.send(commands, deviceId)
                    log.write("$appId ${EventLog.describe(commands)} → ok")
                } catch (error: Exception) {
                    log.write("$appId ${EventLog.describe(commands)} → ${error.message}")
                    throw error
                }
            }
        }
        if (kind == "light") {
            send(TuyaMapper.lightCommands(patch, state, link, codes, cloud.knownRanges[deviceId].orEmpty()))
        } else if (TuyaMapper.isInfraredAC(codes)) {
            try {
                infrared.apply(patch, state, deviceId, send = { send(listOf(it)) }, note = { log.write("$appId $it") })
            } catch (rejected: InfraredRemote.Rejection) {
                throw CommandRejection(rejected.message ?: "Refused", rejected.fields)
            }
        } else {
            send(TuyaMapper.acCommands(patch, link, codes))
        }
    }

    /**
     * What the linked devices report (app device id -> its link), in the shape the panel's
     * `applyRemote` takes; null when the cloud could not be reached.
     */
    suspend fun poll(entries: Map<String, CloudLink>): JSONObject? {
        val cloud = active ?: return null
        if (entries.isEmpty()) return null
        // Devices plus the temperature sensors that stand in for an AC's room reading.
        val ids = entries.values.flatMap { listOfNotNull(it.link.string("deviceId"), it.link.string("sensorId")) }.toSet().toList()
        val status = runCatching { cloud.status(ids) }.getOrNull() ?: return null
        val lights = JSONObject()
        val acs = JSONObject()
        val features = JSONObject()
        for ((id, entry) in entries) {
            val deviceId = entry.link.string("deviceId") ?: continue
            if (entry.kind == "ac") {
                TuyaMapper.acFeatures(cloud.knownCodes[deviceId].orEmpty(), cloud.knownRanges[deviceId].orEmpty())?.let { features.put(id, it) }
            }
            if (entry.kind == "light") {
                status[deviceId]?.let { lights.put(id, TuyaMapper.lightState(it, cloud.knownRanges[deviceId].orEmpty())) }
                continue
            }
            val state = status[deviceId]?.let { TuyaMapper.acState(it) } ?: JSONObject()
            entry.link.string("sensorId")?.let { status[it] }?.let(TuyaMapper::sensorTemperature)?.let { state.put("roomTemp", it) }
            if (state.length() > 0) acs.put(id, state)
        }
        return jsonOf("lights" to lights, "acs" to acs, "features" to features)
    }

    /** Every device of the account, in the editor's terms. */
    suspend fun devices(): List<JSONObject> = listDevices().map(::device)

    /** The account's devices as Tuya reports them (with their data point codes). */
    suspend fun listDevices(): List<JSONObject> {
        val cloud = active ?: throw TuyaFailure("Connect Smart Life first")
        return cloud.listDevices()
    }

    // MARK: - Smart Life account

    /** What the UI may know about the session: never the tokens. */
    val smartLifeInfo: JSONObject get() = smartLife.publicInfo
    val isSmartLifeConnected get() = smartLife.isConfigured
    val userCode get() = smartLife.session?.userCode ?: ""
    val account get() = smartLife.session?.username ?: ""

    /** Asks Tuya for a login ticket: the token, and what the QR code must contain. */
    suspend fun beginLogin(userCode: String) = smartLife.beginLogin(userCode)

    /** True once the user has confirmed the login in the Smart Life app. */
    suspend fun finishLogin(token: String, userCode: String) = runCatching { smartLife.finishLogin(token, userCode) }.getOrDefault(false)

    fun signOut() = smartLife.signOut()

    // MARK: - Tuya Cloud project ("Advanced")

    val tuyaInfo: JSONObject get() = tuya.publicInfo

    /** Stores the project credentials. An empty secret keeps the one already stored; an empty access id removes them. */
    fun saveTuya(accessId: String, accessSecret: String, region: String) {
        val id = accessId.trim()
        val secret = if (accessSecret.isEmpty()) tuya.credentials?.accessSecret ?: "" else accessSecret.trim()
        tuya.save(if (id.isEmpty()) null else TuyaService.Credentials(id, secret, region))
    }

    companion object {
        /** A Smart Life device in the same shape as the other platforms' (plus its Tuya codes). */
        fun device(d: JSONObject): JSONObject {
            val codes = d.optJSONArray("codes").strings().toSet()
            val category = d.optString("category")
            val kind = when {
                category in listOf("kt", "ktkzq", "infrared_ac") || TuyaMapper.isInfraredAC(codes) || "temp_set" in codes -> "ac"
                category in listOf("wsdcg", "wk", "wnykq") || ("temp_current" in codes && "switch_led" !in codes) -> "sensor"
                else -> "light"
            }
            val caps = mutableListOf<String>()
            if ("bright_value" in codes || "bright_value_v2" in codes) caps += "brightness"
            if ("temp_value" in codes || "temp_value_v2" in codes) caps += "temp"
            if ("colour_data" in codes || "colour_data_v2" in codes) caps += "color"
            return JSONObject(d.toString()).apply {
                put("service", "smartlife")
                put("kind", kind)
                put("capabilities", caps.toJsonArray())
                remove("room")
                put("detail", listOf(d.optString("product"), category).filter { it.isNotEmpty() }.joinToString(" · "))
            }
        }

        private fun List<String>.toJsonArray() = org.json.JSONArray(this)
    }
}
