package dev.housecontrol.android.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.Inet6Address

/**
 * Finds Home Assistant servers and Hue Bridges on the local network with DNS-SD (Bonjour),
 * so connecting one is a tap rather than typing an address.
 */
class ServiceDiscovery(context: Context) {
    data class Found(val name: String, val host: String, val port: Int, val txt: Map<String, String>)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Everything advertising `type` ("_hue._tcp", "_home-assistant._tcp") within `millis`. */
    suspend fun browse(type: String, millis: Long = 3000): List<Found> {
        val seen = LinkedHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(seen) { seen[service.serviceName] = service }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(seen) { seen.remove(service.serviceName) }
            }
        }
        val started = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) return emptyList()
        delay(millis)
        runCatching { nsd.stopServiceDiscovery(listener) }
        val services = synchronized(seen) { seen.values.toList() }
        // One resolve at a time: older Android versions refuse a second one while the first runs.
        return services.mapNotNull { resolve(it) }.sortedBy { it.name }
    }

    private suspend fun resolve(service: NsdServiceInfo): Found? = withTimeoutOrNull(3000) {
        val done = CompletableDeferred<NsdServiceInfo?>()
        @Suppress("DEPRECATION")
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                done.complete(null)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                done.complete(info)
            }
        })
        val info = done.await() ?: return@withTimeoutOrNull null
        @Suppress("DEPRECATION")
        val address = info.host ?: return@withTimeoutOrNull null
        var host = address.hostAddress?.substringBefore('%') ?: return@withTimeoutOrNull null
        if (address is Inet6Address) host = "[$host]"
        val txt = info.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8) ?: "" }
        Found(info.serviceName, host, info.port, txt)
    }

    /** Home Assistant servers: their advertised address when they have one. */
    suspend fun homeAssistants(): JSONArray = JSONArray(browse("_home-assistant._tcp").map { found ->
        val url = listOf(found.txt["internal_url"], found.txt["base_url"]).firstOrNull { !it.isNullOrEmpty() }
            ?: "http://${found.host}:${found.port}"
        jsonOf("name" to (found.txt["location_name"] ?: found.name), "url" to url, "version" to (found.txt["version"] ?: ""))
    })

    /** Hue Bridges: DNS-SD first, then Philips' discovery service for networks that block it. */
    suspend fun hueBridges(): JSONArray {
        val out = browse("_hue._tcp").map { found ->
            // Real bridges serve HTTPS on 443; anything else (a test bridge) keeps its port.
            jsonOf("name" to found.name, "host" to if (found.port == 443) found.host else "${found.host}:${found.port}",
                "bridgeId" to (found.txt["bridgeid"] ?: ""))
        }
        if (out.isNotEmpty()) return JSONArray(out)
        val list = runCatching { JSONArray(Http.client.fetch(Http.request("https://discovery.meethue.com/").build()).text) }.getOrNull()
        return JSONArray(list.objects().mapNotNull { b ->
            val ip = b.string("internalipaddress") ?: return@mapNotNull null
            jsonOf("name" to "Hue Bridge", "host" to ip, "bridgeId" to b.optString("id"))
        })
    }
}
