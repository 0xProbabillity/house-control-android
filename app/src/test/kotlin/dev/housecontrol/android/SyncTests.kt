package dev.housecontrol.android

import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.sync.SyncPairing
import dev.housecontrol.android.sync.SyncTransport
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** The pairing link and the encrypted link to the Mac, against a TLS-PSK server like the Mac's. */
class SyncTests {
    private val key = ByteArray(32) { it.toByte() }

    @Test fun pairingLinksRoundTrip() {
        val pairing = SyncPairing("a1b2c3d4-0000", "Studio Mac", key)
        assertEquals(pairing, SyncPairing.parse(pairing.link))
        // The Mac writes base64url without padding; a link with spaces in the name still reads.
        val parsed = SyncPairing.parse("housecontrol://pair?v=1&id=abc&name=My%20Mac&key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        assertEquals("My Mac", parsed?.name)
        assertEquals(key.toList(), parsed?.key?.toList())
        assertNull(SyncPairing.parse("housecontrol://pair?v=2&id=abc&key=AAEC"))
        assertNull(SyncPairing.parse("https://example.com"))
    }

    /** A Mac-like server: TLS 1.2, TLS_PSK_WITH_AES_128_GCM_SHA256 only, the key derived from the pairing. */
    private fun server(serverKey: ByteArray, handle: (TlsServerProtocol) -> Unit): Int {
        val listener = ServerSocket(0)
        thread(isDaemon = true) {
            listener.accept().use { socket ->
                val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
                val identities = object : TlsPSKIdentityManager {
                    override fun getHint(): ByteArray? = null
                    override fun getPSK(identity: ByteArray) = if (String(identity) == "housecontrol") SyncTransport.psk(serverKey) else null
                }
                runCatching {
                    protocol.accept(object : PSKTlsServer(BcTlsCrypto(SecureRandom()), identities) {
                        override fun getSupportedCipherSuites() = intArrayOf(CipherSuite.TLS_PSK_WITH_AES_128_GCM_SHA256)
                        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
                    })
                    handle(protocol)
                }
            }
            listener.close()
        }
        return listener.localPort
    }

    @Test fun messagesTravelOverThePreSharedKeyLink() {
        val port = server(key) { protocol ->
            val hello = SyncTransport.read(protocol.inputStream)!!
            SyncTransport.write(protocol.outputStream, jsonOf("type" to "state", "mac" to "Studio Mac", "rev" to hello.getString("rev"), "plan" to "{\"rooms\":[]}"))
        }
        val (socket, protocol) = SyncTransport.connect("127.0.0.1", port, key)
        SyncTransport.write(protocol.outputStream, jsonOf("type" to "hello", "device" to "Pixel", "rev" to "abc"))
        val state = SyncTransport.read(protocol.inputStream)!!
        assertEquals("abc", state.getString("rev"))
        assertEquals("{\"rooms\":[]}", state.getString("plan"))
        socket.close()
    }

    @Test fun aWrongKeyFailsTheHandshake() {
        val port = server(ByteArray(32) { 7 }) {}
        assertThrows(SyncTransport.HandshakeFailure::class.java) { SyncTransport.connect("127.0.0.1", port, key) }
    }
}
