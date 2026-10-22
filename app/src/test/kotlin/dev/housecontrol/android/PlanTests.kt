package dev.housecontrol.android

import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.core.HomeSnapshot
import dev.housecontrol.android.core.PlanEditor
import dev.housecontrol.android.core.PlanStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The plan as stored, edited without the drawing editor, and as the widgets change it. */
class PlanTests {
    private val demo: JSONObject = JSONObject(File("../web/public/plans/home.json").readText())

    @Test fun planStoreKeepsTheUsersPlanByteForByte() {
        val dir = Files.createTempDirectory("plans").toFile()
        val store = PlanStore(dir)
        assertEquals("home", store.activePlan)
        val data = PlanStore.encode(demo)
        store.saveCustom(data)
        assertEquals("custom", store.activePlan)
        assertEquals(PlanStore.revision(data), store.customPlanRevision)
        assertEquals(16, store.customPlanRevision!!.length)
        // Sorted keys and no escaped slashes, as House Control on the Mac writes it.
        val text = String(data)
        assertTrue(text.indexOf("\"acs\"") < text.indexOf("\"rooms\""))
        assertFalse(text.contains("\\/"))
        assertEquals("custom", PlanStore(dir).activePlan)
        store.removeCustom()
        assertEquals("home", store.activePlan)
        dir.deleteRecursively()
    }

    @Test fun linkingTakesOverWhatTheDeviceCanDo() {
        val editor = PlanEditor(demo)
        val lamp = editor.items.first { it.kind.raw == "light" }
        editor.link(lamp.id, JSONObject("""{"service":"hue","id":"abc","capabilities":["brightness"]}"""))
        val linked = editor.items.first { it.id == lamp.id }
        assertEquals(PlanEditor.DeviceRef("hue", "abc"), linked.link)
        assertEquals("hue", editor.usedDevices.keys.single().service)
        editor.link(lamp.id, JSONObject("""{"service":"smartlife","id":"tuya1"}"""))
        assertEquals(PlanEditor.DeviceRef("smartlife", "tuya1"), editor.items.first { it.id == lamp.id }.link)
        editor.link(lamp.id, null)
        assertNull(editor.items.first { it.id == lamp.id }.link)
    }

    @Test fun addingDevicesSpreadsThemOverTheRoomAndLinksThem() {
        val editor = PlanEditor(PlanEditor.blank("Test"))
        val room = editor.addRoom("Office")
        assertEquals("office", room)
        val ids = editor.add(listOf(
            JSONObject("""{"service":"homeassistant","id":"light.desk","name":"Desk lamp","kind":"light","capabilities":["brightness"]}"""),
            JSONObject("""{"service":"homeassistant","id":"light.ceiling","name":"Ceiling","kind":"light"}"""),
            JSONObject("""{"service":"homeassistant","id":"climate.ac","name":"AC","kind":"ac","min":16,"max":30}"""),
            JSONObject("""{"service":"homeassistant","id":"sensor.t","name":"Thermo","kind":"sensor"}"""),
        ), room)
        assertEquals(listOf("office.light", "office.light2", "office.ac"), ids)
        val desk = editor.plan.getJSONArray("lights").getJSONObject(0)
        assertEquals("table", desk.getString("type"))
        assertEquals("light.desk", desk.getJSONObject("link").getString("id"))
        assertEquals("north", editor.plan.getJSONArray("acs").getJSONObject(0).getString("wall"))
        editor.remove("office.light")
        assertEquals(1, editor.plan.getJSONArray("lights").length())
    }

    private fun snapshot() = HomeSnapshot(JSONObject("""{"name":"Home","plan":"custom","rooms":[{"id":"r","name":"Room","x":0,"z":0,"w":4,"d":3}],
        "lights":[{"id":"a","room":"r","type":"ceiling","capabilities":["brightness","temp"],"on":false,"brightness":1,"temp":3000,"color":"#ff0000",
                   "links":{"link":{"service":"hue","id":"x"}}},
                  {"id":"b","room":"r","type":"table","capabilities":[],"on":true,"brightness":1,"temp":3000,"color":null}],
        "acs":[{"id":"ac","room":"r","wall":"north","min":16,"max":30,"on":false,"target":24,"mode":"cool","fan":"auto"}],
        "scenes":[{"id":"cozy","name":"Cozy","icon":"sofa","lights":{"*":{"on":true,"brightness":0.4,"temp":2700}},"acs":{"ac":{"on":true,"target":22}}}]}"""))

    @Test fun widgetActionsFollowTheStoresRules() {
        val home = snapshot()
        val (resolved, changes) = home.apply(HomeAction(HomeAction.SCENE, "cozy"))
        assertEquals("cozy", home.scene)
        assertEquals(setOf("a", "ac"), changes.map { it.id }.toSet()) // b is on already and can only switch
        val a = changes.first { it.id == "a" }
        assertTrue(a.patch.isNull("color")) // a white temperature drops the colour
        assertEquals(0.4, a.patch.getDouble("brightness"), 0.0)
        assertEquals(22.0, changes.first { it.id == "ac" }.patch.getDouble("target"), 0.0)
        assertEquals(HomeAction.SCENE, resolved.kind)

        val (off, offChanges) = home.apply(HomeAction(HomeAction.ALL_LIGHTS))
        assertEquals(false, off.on) // lights were on: the toggle resolves to off
        assertEquals(2, offChanges.size)
        assertNull(home.scene)
        val (_, clamp) = home.apply(HomeAction(HomeAction.AC_TARGET, "ac", target = 40.0))
        assertEquals(30.0, clamp.single().patch.getDouble("target"), 0.0)
        assertEquals("x", home.linksPlan.getJSONArray("lights").getJSONObject(0).getJSONObject("link").getString("id"))
        assertEquals(false, home.panelState.getJSONObject("lights").getJSONObject("a").getBoolean("on"))
    }
}
