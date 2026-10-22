package dev.housecontrol.android.sync

import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.string
import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a phone needs to find its Mac and talk to it: shown by House Control on the Mac as a
 * QR code (or copied as a link) once, kept encrypted on the phone afterwards.
 */
data class SyncPairing(
    /** Identifies the Mac among the ones advertising on the network. */
    val id: String,
    /** The Mac's name, for display. */
    val name: String,
    /** 32 random bytes; both sides derive the connection's pre-shared key from it. */
    val key: ByteArray,
) {
    fun json() = jsonOf("id" to id, "name" to name, "key" to Base64.getEncoder().encodeToString(key))

    /** `housecontrol://pair?v=1&id=…&name=…&key=…` — the content of the pairing QR code. */
    val link: String
        get() = "$SCHEME://pair?v=1&id=${enc(id)}&name=${enc(name)}&key=${Base64.getUrlEncoder().withoutPadding().encodeToString(key)}"

    override fun equals(other: Any?) = other is SyncPairing && other.id == id && other.name == name && other.key.contentEquals(key)
    override fun hashCode() = id.hashCode()

    companion object {
        const val SCHEME = "housecontrol"

        fun from(json: JSONObject?): SyncPairing? {
            val id = json?.string("id") ?: return null
            val key = runCatching { Base64.getDecoder().decode(json.getString("key")) }.getOrNull() ?: return null
            return if (key.size == 32) SyncPairing(id, json.optString("name", "Mac"), key) else null
        }

        /** Reads a pairing link; null for anything that is not one. */
        fun parse(link: String): SyncPairing? {
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME || uri.host != "pair") return null
            val items = (uri.rawQuery ?: "").split("&").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }.toMap()
            val id = items["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val key = items["key"]?.let { runCatching { Base64.getUrlDecoder().decode(it.trimEnd('=')) }.getOrNull() } ?: return null
            if (items["v"] != "1" || key.size != 32) return null
            return SyncPairing(id, items["name"] ?: "Mac", key)
        }

        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
    }
}

/** What the Mac tells a paired phone. */
data class SyncState(
    val macName: String,
    /** Fingerprint of the Mac's own floor plan; empty when it only has the demo plans. */
    val revision: String,
    /** The plan file byte for byte. Null when the phone already holds `revision`. */
    val plan: ByteArray?,
    /** The Smart Life "User Code" entered on the Mac, to save typing it on the phone. Not a credential. */
    val userCode: String,
    val smartLifeConnected: Boolean,
    /** The Mac's Home Assistant and Hue connections, so the phone controls the same devices. */
    val connections: JSONObject? = null,
) {
    companion object {
        fun from(message: JSONObject): SyncState? {
            if (message.optString("type") != "state") return null
            return SyncState(
                macName = message.optString("mac", "Mac"),
                revision = message.optString("rev"),
                plan = (message.opt("plan") as? String)?.toByteArray(Charsets.UTF_8),
                userCode = message.optString("userCode"),
                smartLifeConnected = message.optBoolean("smartLife"),
                connections = message.optJSONObject("connections"),
            )
        }
    }
}

object SyncTransport {
    const val SERVICE_TYPE = "_housecontrol._tcp"
    const val MAX_FRAME = 8 * 1024 * 1024
    private val IDENTITY = "housecontrol".toByteArray()

    /** The connection's pre-shared key: HMAC-SHA256 of a fixed label under the pairing key. */
    fun psk(key: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("HouseControl sync v1".toByteArray())

    /**
     * TLS 1.2 with a pre-shared key and nothing else (TLS_PSK_WITH_AES_128_GCM_SHA256, as the
     * Mac offers it): a peer that does not hold the pairing key cannot complete the handshake.
     * Blocks; call it off the main thread.
     */
    fun connect(host: String, port: Int, key: ByteArray, timeoutMillis: Int = 8000): Pair<Socket, TlsClientProtocol> {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout = timeoutMillis
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), BasicTlsPSKIdentity(IDENTITY, psk(key))) {
            override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
        }
        try {
            protocol.connect(client)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw HandshakeFailure(e)
        }
        socket.soTimeout = 0 // the Mac only speaks when something changed
        return socket to protocol
    }

    /** The TLS handshake failed: almost always a pairing key the Mac no longer has. */
    class HandshakeFailure(cause: Throwable) : Exception(cause.message, cause)

    /** JSON messages, each prefixed with its length (4 bytes, big endian). */
    fun write(output: OutputStream, message: JSONObject) {
        val body = message.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        body.copyInto(frame, 4)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
    }

    /** The next message; null at the end of the stream or on a frame that makes no sense. */
    fun read(input: InputStream): JSONObject? {
        val data = DataInputStream(input)
        val length = runCatching { data.readInt() }.getOrNull() ?: return null
        if (length <= 0 || length > MAX_FRAME) return null
        val body = ByteArray(length)
        data.readFully(body)
        return runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }
}
