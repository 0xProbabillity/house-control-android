package dev.housecontrol.android

import dev.housecontrol.android.core.DeviceHub
import dev.housecontrol.android.core.MemorySecretStore
import dev.housecontrol.android.smartlife.InfraredRemote
import dev.housecontrol.android.smartlife.SmartLifePlatform
import dev.housecontrol.android.smartlife.SmartLifeService
import dev.housecontrol.android.smartlife.TuyaFailure
import dev.housecontrol.android.smartlife.TuyaMapper
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import javax.crypto.AEADBadTagException

/** The Smart Life module: command building, the infrared remote, the transport, the hub's routing. */
class SmartLifeTests {
    /** Commands as JSON with sorted keys, as the Swift tests compare them. */
    private fun text(commands: List<JSONObject>) =
        commands.joinToString(",", "[", "]") { c -> c.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":${JSONObject.wrap(c.get(it)).let { v -> if (v is String) "\"$v\"" else v }}" } }

    @Test fun infraredACCommands() {
        val ir = setOf("PowerOn", "PowerOff", "T", "M", "F")
        assertEquals("""[{"code":"PowerOn","value":"PowerOn"},{"code":"M","value":1},{"code":"T","value":24}]""",
            text(TuyaMapper.acCommands(JSONObject("""{"on":true,"mode":"heat","target":24.0}"""), JSONObject(), ir)))
        assertEquals("""[{"code":"F","value":3}]""", text(TuyaMapper.acCommands(JSONObject("""{"fan":"high"}"""), JSONObject(), ir)))
    }

    @Test fun bulbCommandsUseTheDeviceRanges() {
        val bulb = setOf("switch_led", "work_mode", "bright_value_v2", "temp_value_v2", "colour_data_v2")
        val ranges = mapOf("bright_value_v2" to (10.0 to 1000.0), "temp_value_v2" to (0.0 to 1000.0))
        assertEquals("""[{"code":"switch_led","value":true},{"code":"bright_value_v2","value":505}]""",
            text(TuyaMapper.lightCommands(JSONObject("""{"on":true,"brightness":0.5}"""), JSONObject(), JSONObject(), bulb, ranges)))
        assertEquals("""[{"code":"switch_1","value":true}]""",
            text(TuyaMapper.lightCommands(JSONObject("""{"on":true}"""), JSONObject(), JSONObject(), emptySet())))
        val state = TuyaMapper.lightState(listOf(JSONObject("""{"code":"switch_led","value":true}"""), JSONObject("""{"code":"bright_value_v2","value":505.0}""")), ranges)
        assertEquals(true, state.get("on"))
        assertEquals(0.5, state.getDouble("brightness"), 0.001)
    }

    @Test fun infraredRemoteKeepsChangesWhileOffAndReplaysAfterARefusal() = runTest {
        val remote = InfraredRemote().apply { spacing = 0 }
        val sent = mutableListOf<String>()
        val notes = mutableListOf<String>()
        // Off: the change waits for the switch-on.
        remote.apply(JSONObject("""{"fan":"high"}"""), JSONObject("""{"on":false,"fan":"high"}"""), "ir", { sent += it.toString() }, { notes += it })
        assertTrue(sent.isEmpty())
        assertTrue(notes.last().contains("kept for the next switch-on"))
        remote.apply(JSONObject("""{"on":true}"""), JSONObject("""{"on":true,"fan":"high","mode":"cool","target":24}"""), "ir", { sent += it.getString("code") }, { notes += it })
        assertEquals(listOf("PowerOn", "F"), sent)
        // A refused set-point: the rest of the state is replayed once, then the key is retried.
        sent.clear()
        var refusals = 0
        remote.apply(JSONObject("""{"target":25}"""), JSONObject("""{"on":true,"mode":"cool","target":25,"fan":"high"}"""), "ir", {
            if (it.getString("code") == "T" && refusals++ == 0) throw TuyaFailure("refused", "2008")
            sent += it.getString("code")
        }, { notes += it })
        assertEquals(listOf("PowerOn", "M", "F", "T"), sent)
        // Refused again after the replay: only the set-point is rolled back.
        val error = assertThrows(InfraredRemote.Rejection::class.java) {
            kotlinx.coroutines.runBlocking {
                remote.apply(JSONObject("""{"target":30}"""), JSONObject("""{"on":true,"target":30}"""), "ir", {
                    if (it.getString("code") == "T") throw TuyaFailure("refused", "2008")
                }, {})
            }
        }
        assertEquals(listOf("target"), error.fields)
    }

    @Test fun transportEnvelopeMatchesTheSwiftClient() {
        val t = SmartLifeService.Transport
        val rid = "11111111-2222-3333-4444-555555555555"
        val secret = t.secret(rid, t.md5Hex(rid + "refresh-token"))
        assertEquals("a43689fcfc68604f", secret) // computed by the Swift client with CryptoKit
        val cipher = t.encrypt("{\"homeId\":\"42\"}", secret, nonce = "ABCDEFGHJKMN")
        assertTrue(cipher.startsWith(Base64.getEncoder().encodeToString("ABCDEFGHJKMN".toByteArray())))
        // The gateway answers with nonce, ciphertext and tag in one base64 string.
        val sealed = Base64.getDecoder().decode(cipher.substring(16))
        val answer = Base64.getEncoder().encodeToString("ABCDEFGHJKMN".toByteArray() + sealed)
        assertEquals("{\"homeId\":\"42\"}", t.decrypt(answer, secret))
        val tampered = Base64.getDecoder().decode(answer).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { t.decrypt(Base64.getEncoder().encodeToString(tampered), secret) }
    }

    @Test fun hubSimulatesUnlinkedDevicesAndReadsLinks() {
        val dir = Files.createTempDirectory("hub").toFile()
        val scope = TestScope(StandardTestDispatcher())
        val hub = DeviceHub(dir, scope, { MemorySecretStore() })
        hub.setLinks(JSONObject("""{"lights":[{"id":"a","tuya":{"deviceId":"dev1"}},{"id":"b"},{"id":"c","tuya":{"deviceId":""}}],
            "acs":[{"id":"ac","tuya":{"deviceId":"dev2","sensorId":"s1"}}]}"""))
        assertEquals(2, hub.linkedDeviceCount)
        assertFalse(hub.isConnected)
        assertFalse(hub.canPoll)
        var reply: JSONObject? = null
        hub.command(JSONObject("""{"id":"a","kind":"light","patch":{"on":true}}""")) { reply = it }
        assertEquals(true, reply?.get("ok")) // not signed in: nothing is sent anywhere
        hub.setLinks(null)
        assertFalse(hub.hasLinks)
        dir.deleteRecursively()
    }

    @Test fun smartLifeDevicesGetAKind() {
        fun device(text: String) = SmartLifePlatform.device(JSONObject(text))
        assertEquals("light", device("""{"id":"1","category":"dj","codes":["switch_led","bright_value_v2","temp_value_v2"]}""").getString("kind"))
        assertEquals("ac", device("""{"id":"2","category":"infrared_ac","codes":["PowerOn","T"]}""").getString("kind"))
        assertEquals("sensor", device("""{"id":"3","category":"wsdcg","codes":["temp_current"]}""").getString("kind"))
        assertEquals(JSONArray(listOf("brightness")).toString(), device("""{"id":"1","category":"dj","codes":["switch_led","bright_value_v2"]}""").getJSONArray("capabilities").toString())
    }
}
