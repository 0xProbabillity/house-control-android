package dev.housecontrol.android

import dev.housecontrol.android.core.ColorMath
import dev.housecontrol.android.core.DeviceKind
import dev.housecontrol.android.core.FanNames
import dev.housecontrol.android.core.HomeAssistantService
import dev.housecontrol.android.core.HueService
import dev.housecontrol.android.core.ServiceFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home Assistant and Hue mappings: the same cases as House Control's Swift ServiceTests. */
class ServiceTests {
    private fun json(text: String) = JSONObject(text)

    @Test fun colourConversionsRoundTrip() {
        for (hex in listOf("#ff0000", "#00ff00", "#0000ff", "#ffa040", "#8050ff")) {
            val (h, s, _) = ColorMath.hsv(hex)!!
            assertEquals(hex, ColorMath.fromHue(h, s))
            val (x, y) = ColorMath.xy(hex)!!
            val back = ColorMath.rgb(ColorMath.fromXY(x, y))!!
            val orig = ColorMath.rgb(hex)!!
            assertEquals(orig.first, back.first, 0.03)
            assertEquals(orig.second, back.second, 0.03)
            assertEquals(orig.third, back.third, 0.03)
        }
        assertEquals(400.0, ColorMath.mired(2500.0), 0.0)
        assertEquals(4000.0, ColorMath.kelvin(250.0), 0.0)
        assertNull(ColorMath.rgb("red"))
    }

    @Test fun homeAssistantListsLightsClimateAndSensors() {
        val light = HomeAssistantService.device(json("""{"entity_id":"light.bed_light","state":"off","attributes":{"friendly_name":"Bed Light","supported_color_modes":["color_temp","hs"]}}"""))!!
        assertEquals(DeviceKind.LIGHT, light.kind)
        assertEquals(listOf("brightness", "temp", "color"), light.capabilities)
        val plug = HomeAssistantService.device(json("""{"entity_id":"switch.decorative_lights","state":"unavailable","attributes":{}}"""))!!
        assertEquals(emptyList<String>(), plug.capabilities)
        assertFalse(plug.online)
        val ac = HomeAssistantService.device(json("""{"entity_id":"climate.hvac","state":"cool","attributes":{"min_temp":7,"max_temp":35,"hvac_modes":["off","cool"]}}"""))!!
        assertEquals(DeviceKind.AC, ac.kind)
        assertEquals(10.0 to 32.0, ac.range)
        assertEquals(DeviceKind.SENSOR, HomeAssistantService.device(json("""{"entity_id":"sensor.outside","state":"15.6","attributes":{"unit_of_measurement":"°C"}}"""))?.kind)
        assertNull(HomeAssistantService.device(json("""{"entity_id":"sensor.power","state":"3","attributes":{"unit_of_measurement":"W"}}""")))
        assertNull(HomeAssistantService.device(json("""{"entity_id":"media_player.tv","state":"on","attributes":{}}""")))
    }

    @Test fun homeAssistantReadsLightState() {
        val white = HomeAssistantService.reading(json("""{"entity_id":"light.ceiling","state":"on","attributes":{"brightness":255,"color_mode":"color_temp","color_temp_kelvin":2631}}""")).light!!
        assertEquals(true, white.get("on"))
        assertEquals(1.0, white.getDouble("brightness"), 0.0)
        assertEquals(2631.0, white.getDouble("temp"), 0.0)
        assertTrue(white.isNull("color"))
        val colour = HomeAssistantService.reading(json("""{"entity_id":"light.kitchen","state":"on","attributes":{"brightness":180,"color_mode":"hs","rgb_color":[255,64,112]}}""")).light!!
        assertEquals("#ff4070", colour.getString("color"))
        assertFalse(HomeAssistantService.reading(json("""{"entity_id":"light.x","state":"unavailable","attributes":{}}""")).online)
    }

    @Test fun homeAssistantReadsClimate() {
        val reading = HomeAssistantService.reading(json("""{"entity_id":"climate.hvac","state":"fan_only","attributes":{
            "hvac_modes":["off","heat","cool","auto","dry","fan_only"],"temperature":21,"current_temperature":22,
            "fan_modes":["on_low","on_high","auto_low","auto_high","off"],"fan_mode":"on_high","swing_modes":["auto","1","off"],
            "swing_mode":"off","preset_modes":["home","eco"],"preset_mode":"eco"}}"""))
        val ac = reading.ac!!
        assertEquals(true, ac.get("on"))
        assertEquals("fan", ac.get("mode"))
        assertEquals(21.0, ac.getDouble("target"), 0.0)
        assertEquals(22.0, ac.getDouble("roomTemp"), 0.0)
        assertEquals("high", ac.get("fan"))
        assertEquals("off", ac.get("swing"))
        assertEquals(true, ac.get("eco"))
        assertEquals(false, ac.get("idle"))
        assertEquals(JSONArray(listOf("cool", "heat", "dry", "fan", "auto")).toString(), reading.features!!.getJSONArray("modes").toString())
        assertEquals(JSONArray(listOf("low", "high")).toString(), reading.features!!.getJSONArray("fans").toString())
        fun idle(state: String, action: String?): Boolean? {
            val attributes = JSONObject().put("hvac_modes", JSONArray(listOf("off", "heat"))).put("temperature", 21)
            if (action != null) attributes.put("hvac_action", action)
            return HomeAssistantService.reading(JSONObject().put("entity_id", "climate.heater").put("state", state).put("attributes", attributes)).ac?.optBoolean("idle")
        }
        assertEquals(true, idle("heat", "idle"))
        assertEquals(false, idle("heat", "heating"))
        assertEquals(false, idle("heat", null))
        assertEquals(false, idle("off", "off"))
        val range = HomeAssistantService.reading(json("""{"entity_id":"climate.ecobee","state":"heat_cool","attributes":{"hvac_modes":["off","heat_cool"],"target_temp_low":21,"target_temp_high":24}}"""))
        assertEquals("auto", range.ac!!.get("mode"))
        assertEquals(23.0, range.ac!!.getDouble("target"), 0.0)
    }

    @Test fun homeAssistantBuildsServiceCalls() {
        val none = JSONObject()
        assertEquals(listOf("turn_off"), HomeAssistantService.calls("light.a", json("""{"on":false}"""), none, emptyMap(), emptyMap()).map { it.service })
        val dim = HomeAssistantService.calls("light.a", json("""{"brightness":0.5,"temp":3000.0}"""), none, emptyMap(), emptyMap()).first()
        assertEquals("turn_on", dim.service)
        assertEquals(50, dim.data.getInt("brightness_pct"))
        assertEquals(3000, dim.data.getInt("color_temp_kelvin"))
        assertEquals("light.a", dim.data.getString("entity_id"))
        val colour = HomeAssistantService.calls("light.a", json("""{"color":"#ff8000"}"""), none, emptyMap(), emptyMap()).first()
        assertEquals("[255,128,0]", colour.data.getJSONArray("rgb_color").toString())
        assertEquals(listOf("turn_on"), HomeAssistantService.calls("switch.p", json("""{"on":true}"""), none, emptyMap(), emptyMap()).map { it.service })

        val modes = HomeAssistantService.modeTable(listOf("off", "cool", "heat_cool", "fan_only"))
        assertEquals(mapOf("cool" to "cool", "auto" to "heat_cool", "fan" to "fan_only"), modes)
        val fans = FanNames.table(listOf("on_low", "on_high", "auto_low", "off"))
        assertEquals(mapOf("low" to "on_low", "high" to "on_high"), fans)
        val on = HomeAssistantService.calls("climate.x", json("""{"on":true}"""), json("""{"mode":"auto"}"""), fans, modes)
        assertEquals("heat_cool", on.first().data.getString("hvac_mode"))
        val change = HomeAssistantService.calls("climate.x", json("""{"target":23.0,"fan":"high","eco":true}"""), json("""{"mode":"cool"}"""), fans, modes)
        assertEquals(setOf("set_temperature", "set_fan_mode", "set_preset_mode"), change.map { it.service }.toSet())
        assertEquals("off", HomeAssistantService.calls("climate.x", json("""{"on":false}"""), none, fans, modes).first().data.getString("hvac_mode"))
    }

    @Test fun homeAssistantAddressIsNormalized() {
        assertEquals("http://homeassistant.local:8123", HomeAssistantService.normalize("homeassistant.local"))
        assertEquals("http://10.0.0.5:8123", HomeAssistantService.normalize(" http://10.0.0.5:8123/ "))
        assertEquals("https://ha.example.com", HomeAssistantService.normalize("https://ha.example.com"))
        assertThrows(ServiceFailure::class.java) { HomeAssistantService.normalize("") }
    }

    @Test fun hueReadsAndWritesLights() {
        val white = json("""{"id":"l1","metadata":{"name":"Desk","archetype":"classic_bulb"},"on":{"on":true},"dimming":{"brightness":50.0},
            "color_temperature":{"mirek":400,"mirek_valid":true},"color":{"xy":{"x":0.5,"y":0.4}}}""")
        val state = HueService.lightState(white)
        assertEquals(true, state.get("on"))
        assertEquals(0.5, state.getDouble("brightness"), 0.0)
        assertEquals(2500.0, state.getDouble("temp"), 0.0)
        val colour = HueService.lightState(json("""{"on":{"on":true},"color_temperature":{"mirek":null,"mirek_valid":false},"color":{"xy":{"x":0.6915,"y":0.3083}}}"""))
        assertEquals("#ff0000", colour.getString("color"))
        assertEquals(listOf("brightness", "temp", "color"), HueService.device(white)!!.capabilities)
        assertEquals(emptyList<String>(), HueService.device(json("""{"id":"p","on":{"on":false}}"""))!!.capabilities)

        val body = HueService.lightBody(json("""{"on":true,"brightness":0.42,"temp":2500.0,"color":"#ff0000"}"""))
        assertEquals(true, body.getJSONObject("on").get("on"))
        assertEquals(42.0, body.getJSONObject("dimming").getDouble("brightness"), 0.0)
        assertEquals(400, body.getJSONObject("color_temperature").getInt("mirek"))
        assertEquals(0.64, body.getJSONObject("color").getJSONObject("xy").getDouble("x"), 0.01)
        assertEquals(mapOf("dev1" to "Living"), HueService.roomIndex(listOf(json("""{"metadata":{"name":"Living"},"children":[{"rid":"dev1","rtype":"device"}]}"""))))
        assertEquals(21.5, HueService.temperature(json("""{"temperature":{"temperature_report":{"temperature":21.5}}}"""))!!, 0.0)
    }
}
