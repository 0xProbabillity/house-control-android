package dev.housecontrol.android.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.housecontrol.android.core.SecretStore
import dev.housecontrol.android.core.jsonOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bouncycastle.tls.TlsClientProtocol
import org.json.JSONObject
import java.net.Socket

/**
 * The phone's side of the sync with House Control on a Mac: finds the paired Mac on the
 * local network, stays connected while the app is open and receives the floor plan whenever
 * it changes. The same protocol as the iPhone app's `SyncClient`.
 */
class SyncClient(private val context: Context, private val store: SecretStore, private val scope: CoroutineScope) {
    sealed class Status {
        /** No Mac has been paired yet. */
        data object Unpaired : Status()
        /** Looking for the Mac on this network. */
        data object Searching : Status()
        data object Connecting : Status()
        data object Connected : Status()
        /** The Mac was found but would not talk to us; the text says why. */
        data class Failed(val reason: String) : Status()
    }

    var pairing: SyncPairing? = store.readText()?.let { runCatching { SyncPairing.from(JSONObject(it)) }.getOrNull() }
        private set
    var status: Status = if (pairing == null) Status.Unpaired else Status.Searching
        private set
    private var running = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var channel: Channel? = null
    private var retry: Job? = null
    /** What the Mac should be showing for this phone, and whether it has been told yet. */
    private var qrContent: String? = null
    private var qrUnsent = false
    /** This phone's Home Assistant and Hue connections and Smart Life User Code, waiting to be sent. */
    private var connections: Pair<JSONObject, String>? = null
    /** Set to connect straight to a host and port instead of searching (an emulator, tests). */
    var fixedEndpoint: Pair<String, Int>? = null

    /** This device's name as the Mac shows it. */
    var deviceName = "Android"
    /** The revision of the plan this device already holds, so the Mac need not resend it. */
    var planRevision: (() -> String?)? = null
    var onStatus: (() -> Unit)? = null
    var onState: ((SyncState) -> Unit)? = null

    val isPaired get() = pairing != null
    val isConnected get() = status == Status.Connected

    private val nsd by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    fun pair(next: SyncPairing) {
        teardown()
        pairing = next
        store.writeText(next.json().toString())
        set(Status.Searching)
        if (running) search()
    }

    fun unpair() {
        qrContent = null
        qrUnsent = false
        teardown()
        pairing = null
        store.write(null)
        set(Status.Unpaired)
    }

    /** Call when the app comes to the foreground. */
    fun start() {
        if (running) return
        running = true
        if (pairing != null) search()
    }

    /** Call when the app goes to the background. */
    fun stop() {
        running = false
        teardown()
        if (pairing != null) set(Status.Searching)
    }

    /** Asks the Mac for its current state again. */
    fun refresh() {
        if (!running || pairing == null) return
        val open = channel
        if (open != null && status == Status.Connected) hello(open) else { teardown(); search() }
    }

    /**
     * Asks the Mac to display `content` as a QR code (null takes it down again): the Smart Life
     * login, which has to be scanned by the Smart Life app on this phone. False when the Mac
     * is not connected right now; the request is then delivered as soon as it is.
     */
    fun showQR(content: String?): Boolean {
        qrContent = content
        qrUnsent = true
        return flushQR()
    }

    /** Hands the Mac this phone's connections, now or as soon as it is connected. */
    fun sendConnections(shared: JSONObject, userCode: String) {
        connections = shared to userCode
        flushConnections()
    }

    /**
     * Sends a floor plan edited on this phone to the Mac (the file byte for byte, so both
     * sides compute the same revision). Not queued: false when the Mac is not connected
     * right now, and the next state it sends decides what happens to the edit.
     */
    fun sendPlan(data: ByteArray): Boolean {
        val open = channel ?: return false
        if (status != Status.Connected) return false
        open.send(jsonOf("type" to "plan", "plan" to String(data, Charsets.UTF_8)))
        return true
    }

    private fun flushConnections() {
        val (shared, userCode) = connections ?: return
        val open = channel ?: return
        if (status != Status.Connected) return
        open.send(jsonOf("type" to "connections", "connections" to shared, "userCode" to userCode))
        connections = null
    }

    private fun flushQR(): Boolean {
        val open = channel ?: return false
        if (!qrUnsent || status != Status.Connected) return false
        open.send(JSONObject().put("type", "showQR").apply { qrContent?.let { put("content", it) } })
        qrUnsent = false
        return true
    }

    // MARK: - Connection

    private fun set(next: Status) {
        if (next == status) return
        status = next
        onStatus?.invoke()
    }

    private fun teardown() {
        retry?.cancel()
        retry = null
        stopDiscovery()
        channel?.close()
        channel = null
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    private fun search() {
        val pairing = pairing ?: return
        if (!running || channel != null) return
        fixedEndpoint?.let { (host, port) ->
            connect(host, port, pairing)
            return
        }
        if (discovery != null) return
        if (status !is Status.Failed) set(Status.Searching)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                scope.launch { discovery = null; scheduleRetry() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        scope.launch {
                            val current = this@SyncClient.pairing ?: return@launch
                            if (id == current.id && channel == null && running) {
                                stopDiscovery()
                                connect(host, info.port, current)
                            }
                        }
                    }
                })
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(SyncTransport.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null; scheduleRetry() }
    }

    private fun connect(host: String, port: Int, pairing: SyncPairing) {
        set(Status.Connecting)
        val open = Channel(host, port, pairing.key)
        channel = open
        open.onReady = { hello(open) }
        open.onMessage = { message ->
            SyncState.from(message)?.let { state ->
                set(Status.Connected)
                flushQR()
                flushConnections()
                onState?.invoke(state)
            }
        }
        open.onClose = { error ->
            if (channel === open) {
                channel = null
                // The handshake fails when the Mac's key was reset since this phone was paired.
                if (error is SyncTransport.HandshakeFailure) {
                    set(Status.Failed("${pairing.name} did not accept this pairing. Pair again on the computer: Pair Phone… in House Control's menu"))
                } else if (running) {
                    set(Status.Searching)
                }
                scheduleRetry()
            }
        }
        open.start()
    }

    private fun hello(open: Channel) {
        open.send(jsonOf("type" to "hello", "device" to deviceName, "rev" to (planRevision?.invoke() ?: "")))
    }

    private fun scheduleRetry() {
        if (!running || pairing == null) return
        retry?.cancel()
        stopDiscovery()
        retry = scope.launch {
            delay(3000)
            search()
        }
    }

    /** One connection to the Mac: a reader thread, writes from anywhere, callbacks on the main thread. */
    private inner class Channel(private val host: String, private val port: Int, private val key: ByteArray) {
        var onReady: (() -> Unit)? = null
        var onMessage: ((JSONObject) -> Unit)? = null
        var onClose: ((Throwable?) -> Unit)? = null
        @Volatile private var socket: Socket? = null
        @Volatile private var protocol: TlsClientProtocol? = null
        @Volatile private var closed = false
        /** True once `onClose` was called, or the owner closed the channel itself. */
        private var finished = false

        fun start() {
            scope.launch(Dispatchers.IO) {
                var failure: Throwable? = null
                try {
                    val (s, p) = SyncTransport.connect(host, port, key)
                    socket = s
                    protocol = p
                    if (closed) return@launch
                    scope.launch { onReady?.invoke() }
                    while (!closed) {
                        val message = SyncTransport.read(p.inputStream) ?: break
                        scope.launch { if (!closed) onMessage?.invoke(message) }
                    }
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    shut()
                    scope.launch { if (!finished) { finished = true; onClose?.invoke(failure) } }
                }
            }
        }

        fun send(message: JSONObject) {
            val p = protocol ?: return
            if (closed) return
            scope.launch(Dispatchers.IO) { runCatching { SyncTransport.write(p.outputStream, message) } }
        }

        fun close() {
            closed = true
            finished = true
            scope.launch(Dispatchers.IO) { shut() }
        }

        private fun shut() {
            runCatching { protocol?.close() }
            runCatching { socket?.close() }
        }
    }
}
