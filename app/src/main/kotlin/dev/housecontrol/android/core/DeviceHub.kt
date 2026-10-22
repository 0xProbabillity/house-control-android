package dev.housecontrol.android.core

import dev.housecontrol.android.smartlife.CloudLink
import dev.housecontrol.android.smartlife.CommandRejection
import dev.housecontrol.android.smartlife.SmartLifePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Everything between the UI and the real devices: which platforms are connected
 * (Smart Life, Home Assistant, Philips Hue), which device of the plan is linked to which
 * real device, sending the changes made in the panel and reading the devices' state back.
 * A port of House Control's `DeviceHub`; it runs on `scope`, the main thread.
 */
class DeviceHub(
    directory: File,
    private val scope: CoroutineScope,
    secret: (String) -> SecretStore,
    /** Smart Life, in the builds that include it. */
    val cloud: SmartLifePlatform? = SmartLifePlatform(secret("smartlife"), secret("tuya")),
) {
    val log = EventLog(directory)
    val homeAssistant = HomeAssistantService(secret("homeassistant"), scope)
    val hue = HueService(secret("hue"), scope)
    /** The platforms other than Smart Life, by the id a plan's `link.service` uses. */
    val services: List<DeviceService> = listOf(homeAssistant, hue)
    private fun service(id: String) = services.firstOrNull { it.id == id }
    /** Smart Life when it is part of this build and signed in. */
    private val connectedCloud get() = cloud?.takeIf { it.isConnected }

    /** App device id -> its Smart Life link, for the devices of the active plan. */
    private var links: Map<String, CloudLink> = emptyMap()

    /** App device id -> its link to another platform. */
    private data class Link(
        val kind: DeviceKind,
        val service: String,
        val id: String,
        /** A temperature sensor for an AC's room reading, on any platform. */
        val sensor: Pair<String, String>?,
    )

    private var serviceLinks: Map<String, Link> = emptyMap()
    /** Sensors from other platforms that report the room of a Smart Life AC. */
    private var tuyaSensors: Map<String, Pair<String, String>> = emptyMap()
    private var refresh: Job? = null
    /** The command in flight for each real device; the next one waits for it. */
    private val commandTails = HashMap<String, Job>()
    private val stampsFile = File(directory, "connections-sync.json")

    /**
     * Called after a command failed in a way that leaves the panel out of step with the
     * devices, so the owner can read their state again.
     */
    var onStale: (() -> Unit)? = null

    init {
        for (service in services) service.onChange = { scheduleRefresh() }
    }

    /** Changes reported by a platform arrive in bursts: one read for all of them. */
    private fun scheduleRefresh() {
        refresh?.cancel()
        refresh = scope.launch {
            delay(300)
            onStale?.invoke()
        }
    }

    // MARK: - Links

    val isConnected get() = connectedCloud != null || services.any { it.isConnected }
    val hasLinks get() = links.isNotEmpty() || serviceLinks.isNotEmpty()

    /** True when there is something to read at all. */
    val canPoll: Boolean
        get() = (connectedCloud != null && links.isNotEmpty()) ||
            serviceLinks.values.any { service(it.service)?.isConnected == true }

    val linkedDeviceCount get() = links.size + serviceLinks.values.count { it.id.isNotEmpty() }

    /** Takes the device links from `plan`; null (a bundled demo) means everything is simulated. */
    fun setLinks(plan: JSONObject?) {
        val cloudLinks = HashMap<String, CloudLink>()
        val others = HashMap<String, Link>()
        val sensors = HashMap<String, Pair<String, String>>()
        if (plan != null) {
            for ((kind, key) in listOf("light" to "lights", "ac" to "acs")) {
                for (device in plan.objects(key)) {
                    val id = device.string("id") ?: continue
                    val sensor = sensorLink(device.optJSONObject("sensor"))
                    val link = device.optJSONObject("link")
                    val service = link?.string("service")
                    val target = link?.string("id")
                    val tuya = device.optJSONObject("tuya")
                    if (service != null && target != null) {
                        others[id] = Link(DeviceKind.of(kind) ?: DeviceKind.LIGHT, service, target, sensor)
                    } else if (cloud != null && tuya?.string("deviceId") != null) {
                        cloudLinks[id] = CloudLink(kind, tuya)
                        if (sensor != null) sensors[id] = sensor
                    } else if (sensor != null && kind == "ac") {
                        // A simulated AC can still show a real room temperature.
                        others[id] = Link(DeviceKind.AC, "", "", sensor)
                    }
                }
            }
        }
        links = cloudLinks
        serviceLinks = others
        tuyaSensors = sensors
        watchLinks()
    }

    /** `{"service": "hue", "id": "…"}` in a plan. */
    private fun sensorLink(value: JSONObject?): Pair<String, String>? {
        val service = value?.optString("service") ?: return null
        val id = value.string("id") ?: return null
        return service to id
    }

    /** Tells each platform which of its devices the plan uses, for its live updates. */
    private fun watchLinks() {
        val ids = wantedIds()
        for (service in services) service.watch(ids[service.id].orEmpty())
    }

    private fun wantedIds(): Map<String, Set<String>> {
        val ids = HashMap<String, MutableSet<String>>()
        for (link in serviceLinks.values) {
            if (link.id.isNotEmpty()) ids.getOrPut(link.service) { mutableSetOf() } += link.id
            link.sensor?.let { ids.getOrPut(it.first) { mutableSetOf() } += it.second }
        }
        for (sensor in tuyaSensors.values) ids.getOrPut(sensor.first) { mutableSetOf() } += sensor.second
        return ids
    }

    // MARK: - Commands

    /**
     * Sends a change made in the panel to the real device and tells the panel how it went,
     * so a rejected change can be rolled back there. Commands to one device run strictly
     * one after another, in the order this is called.
     */
    fun command(message: JSONObject, reply: (JSONObject) -> Unit) {
        val id = message.string("id")
        val link = id?.let { serviceLinks[it] }
        if (id != null && link != null && link.id.isNotEmpty()) {
            commandService(link, id, message, reply)
            return
        }
        val cloud = connectedCloud
        val entry = id?.let { links[it] }
        val deviceId = entry?.link?.string("deviceId")
        if (cloud == null || entry == null || deviceId == null) {
            reply(jsonOf("ok" to true)) // simulated device: nothing to send
            return
        }
        val patch = message.optJSONObject("patch") ?: JSONObject()
        val state = message.optJSONObject("state") ?: JSONObject()
        chain(deviceId) {
            try {
                cloud.command(id, entry.kind, entry.link, patch, state, log)
                reply(jsonOf("ok" to true))
            } catch (rejected: CommandRejection) {
                reply(jsonOf("error" to rejected.message, "failed" to rejected.fields))
            } catch (error: Exception) {
                reply(jsonOf("error" to error.readable))
                // The panel showed the change optimistically: pull the real state back in.
                onStale?.invoke()
            }
        }
    }

    private fun commandService(link: Link, appId: String, message: JSONObject, reply: (JSONObject) -> Unit) {
        val service = service(link.service)
        if (service == null || !service.isConnected) {
            reply(jsonOf("ok" to true)) // not reachable from here: simulated
            return
        }
        val patch = message.optJSONObject("patch") ?: JSONObject()
        val state = message.optJSONObject("state") ?: JSONObject()
        chain("${link.service}:${link.id}") {
            val what = patch.keyList().sorted().joinToString(" ") { "$it=${patch.opt(it)}" }
            try {
                service.send(link.kind, link.id, patch, state)
                log.write("$appId [${service.title}] $what → ok")
                reply(jsonOf("ok" to true))
            } catch (error: Exception) {
                log.write("$appId [${service.title}] $what → ${error.readable}")
                reply(jsonOf("error" to error.readable))
                onStale?.invoke()
            }
        }
    }

    /** Runs `work` after whatever was sent to the same device before it. */
    private fun chain(key: String, work: suspend () -> Unit) {
        val previous = commandTails[key]
        val job = scope.launch {
            previous?.join()
            work()
        }
        commandTails[key] = job
        job.invokeOnCompletion { scope.launch { if (commandTails[key] === job) commandTails.remove(key) } }
    }

    // MARK: - State

    /**
     * What the linked devices report right now, in the shape the panel's `applyRemote`
     * takes. Null when nothing is connected or the cloud could not be reached.
     */
    suspend fun poll(): JSONObject? = coroutineScope {
        val tuyaPart = async { pollCloud() }
        val servicePart = async { pollServices() }
        val fromTuya = tuyaPart.await()
        val fromServices = servicePart.await()
        if (fromTuya == null && fromServices == null) return@coroutineScope null
        val merged = JSONObject()
        for (key in listOf("lights", "acs", "features")) {
            val section = fromTuya?.optJSONObject(key)?.copy() ?: JSONObject()
            val more = fromServices?.optJSONObject(key) ?: JSONObject()
            for (id in more.keyList()) {
                val existing = section.optJSONObject(id)
                val value = more.get(id)
                if (existing != null && value is JSONObject) existing.mergeFrom(value) else section.put(id, value)
            }
            merged.put(key, section)
        }
        merged
    }

    /** Home Assistant and Hue, each asked once for all of its linked devices. */
    private suspend fun pollServices(): JSONObject? {
        val readings = HashMap<String, Map<String, DeviceReading>>()
        var answered = false
        for ((serviceId, ids) in wantedIds()) {
            val service = service(serviceId) ?: continue
            if (!service.isConnected) continue
            val result = runCatching { service.read(ids) }.getOrNull() ?: continue
            readings[serviceId] = result
            answered = true
        }
        if (!answered) return null
        val lights = JSONObject()
        val acs = JSONObject()
        val features = JSONObject()
        for ((id, link) in serviceLinks) {
            val reading = readings[link.service]?.get(link.id)
            if (link.kind == DeviceKind.LIGHT && reading?.online != false) reading?.light?.let { lights.put(id, it) }
            if (link.kind == DeviceKind.AC) {
                val ac = if (reading?.online == false) JSONObject() else reading?.ac?.copy() ?: JSONObject()
                link.sensor?.let { (service, sensor) -> readings[service]?.get(sensor)?.temperature }?.let { ac.put("roomTemp", it) }
                if (ac.length() > 0) acs.put(id, ac)
                reading?.features?.let { features.put(id, it) }
            }
        }
        for ((id, sensor) in tuyaSensors) {
            readings[sensor.first]?.get(sensor.second)?.temperature?.let { acs.put(id, jsonOf("roomTemp" to it)) }
        }
        return jsonOf("lights" to lights, "acs" to acs, "features" to features)
    }

    private suspend fun pollCloud(): JSONObject? {
        val cloud = connectedCloud ?: return null
        if (links.isEmpty()) return null
        return cloud.poll(links)
    }

    /**
     * Every device of every connected platform, in the editor's terms, plus what went
     * wrong with the platforms that could not be asked.
     */
    suspend fun allDevices(): Pair<List<JSONObject>, Map<String, String>> {
        val devices = mutableListOf<JSONObject>()
        val errors = HashMap<String, String>()
        connectedCloud?.let { cloud ->
            try {
                devices += cloud.devices()
            } catch (error: Exception) {
                errors["smartlife"] = error.readable
            }
        }
        for (service in services) {
            if (!service.isConnected) continue
            try {
                devices += service.devices().map { it.json(service.id) }
            } catch (error: Exception) {
                errors[service.id] = error.readable
            }
        }
        return devices to errors
    }

    /** What the editor and the settings show for each platform. */
    val servicesInfo: JSONObject
        get() = JSONObject().apply {
            cloud?.let { put("smartlife", it.info) }
            for (service in services) put(service.id, service.info)
        }

    // MARK: - Connections shared with a paired Mac

    /**
     * When each shareable platform was last connected or disconnected on this device, or
     * adopted from another one: the newer side wins when two devices disagree.
     */
    private var stamps: Map<String, Double>
        get() = runCatching {
            val json = JSONObject(stampsFile.readText())
            json.keyList().associateWith { json.getDouble(it) }
        }.getOrDefault(emptyMap())
        set(value) {
            runCatching { stampsFile.writeText(JSONObject(value).toString()) }
        }

    private fun shareable(id: String): JSONObject? = when (id) {
        "homeassistant" -> homeAssistant.shareable
        "hue" -> hue.shareable
        else -> null
    }

    /** Connects Home Assistant (checking the address and token first) and remembers when. */
    suspend fun connectHomeAssistant(url: String, token: String) {
        homeAssistant.connect(url, token)
        stamp("homeassistant")
    }

    /** Pairs with a Hue Bridge; throws [HueService.ButtonNotPressed] until its button is pressed. */
    suspend fun pairHue(host: String, bridgeId: String?, name: String?) {
        hue.pair(host, bridgeId, name)
        stamp("hue")
    }

    /** Disconnects a platform; for Home Assistant and Hue the paired Mac follows. */
    fun disconnect(id: String) {
        services.firstOrNull { it.id == id }?.disconnect()
        if (id in SHAREABLE) stamp(id)
    }

    private fun stamp(id: String) {
        val all = stamps.toMutableMap()
        all[id] = maxOf(System.currentTimeMillis() / 1000.0, (all[id] ?: 0.0) + 0.001)
        stamps = all
    }

    /**
     * This device's Home Assistant and Hue connections for its paired Mac: the address and
     * token / bridge key plus when they were set, or `removed` after a disconnect.
     */
    val shareableConnections: JSONObject
        get() {
            val known = stamps
            val out = JSONObject()
            for (id in SHAREABLE) {
                val updated = known[id] ?: 0.0
                val config = shareable(id)
                if (config != null) out.put(id, config.put("updated", updated))
                else if (updated > 0) out.put(id, jsonOf("removed" to true, "updated" to updated))
            }
            return out
        }

    /**
     * Takes what another device shared, platform by platform, where it is newer than what
     * this device has. A platform missing from `shared` is left alone. Returns true when a
     * connection changed here.
     */
    fun adoptConnections(shared: JSONObject): Boolean {
        val known = stamps.toMutableMap()
        var changed = false
        for (id in SHAREABLE) {
            val incoming = shared.optJSONObject(id) ?: continue
            val theirs = incoming.double("updated") ?: 0.0
            val ours = known[id]
            // A peer without stamps (an older version) only fills in what was never set here.
            val newer = if (theirs > 0) theirs > (ours ?: 0.0) else (ours ?: 0.0) == 0.0
            if (!newer) continue
            val before = shareable(id)?.toString()
            when {
                incoming.bool("removed") == true -> services.firstOrNull { it.id == id }?.disconnect()
                id == "homeassistant" -> homeAssistant.adopt(incoming)
                else -> hue.adopt(incoming)
            }
            known[id] = theirs
            if (shareable(id)?.toString() != before) changed = true
        }
        stamps = known
        return changed
    }

    companion object {
        /**
         * The platforms whose connection travels between paired devices. (A Smart Life session
         * cannot be shared: Tuya rotates its tokens.)
         */
        val SHAREABLE = listOf("homeassistant", "hue")
    }
}
