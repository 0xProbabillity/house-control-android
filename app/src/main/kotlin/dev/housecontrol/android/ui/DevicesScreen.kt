package dev.housecontrol.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.R
import dev.housecontrol.android.core.DeviceKind
import dev.housecontrol.android.core.PlanEditor
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A device of a connected platform, as `DeviceHub.allDevices` lists it. */
data class RealDevice(val raw: JSONObject) {
    val service: String get() = raw.optString("service")
    val deviceId: String get() = raw.optString("id")
    val ref get() = PlanEditor.DeviceRef(service, deviceId)
    val name: String get() = raw.optString("name").ifEmpty { deviceId }
    val kind: String get() = raw.optString("kind", "light")
    val online get() = raw.optBoolean("online", true)
    val detail: String get() = listOf(raw.optString("room"), raw.optString("detail")).filter { it.isNotEmpty() }.joinToString(" · ")
}

fun serviceTint(service: String) = when (service) {
    "smartlife" -> Palette.smartLife
    "homeassistant" -> Palette.homeAssistant
    "hue" -> Palette.hue
    else -> Palette.warm
}

fun serviceIcon(service: String) = when (service) {
    "smartlife" -> R.drawable.lucide_plug
    "homeassistant" -> R.drawable.lucide_house_wifi
    else -> R.drawable.lucide_lightbulb
}

fun serviceTitle(service: String) = when (service) {
    "smartlife" -> "Smart Life"
    "homeassistant" -> "Home Assistant"
    "hue" -> "Philips Hue"
    else -> "Apple Home"
}

/** What a picker is choosing: the device of a lamp or AC, or an AC's thermometer. */
private data class Picking(val item: PlanEditor.Item, val sensor: Boolean)

/**
 * Every lamp and AC of the plan and the real device it is linked to; tapping one picks its
 * device from the list of everything the connected platforms have.
 */
@Composable
fun DevicesScreen(model: AppModel, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(listOf<RealDevice>()) }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun load() {
        loading = true
        scope.launch {
            val (list, problems) = model.allDevices()
            devices = list.map(::RealDevice).sortedBy { it.name.lowercase() }
            errors = problems
            loading = false
            loaded = true
        }
    }
    LaunchedEffect(Unit) { if (model.hasAnyConnection) load() }

    val editor = remember(model.planVersion) { model.currentPlan?.let(::PlanEditor) }
    val current = picking
    if (current != null) {
        BackHandler { picking = null }
        val kind = if (current.sensor) "sensor" else if (current.item.kind == DeviceKind.AC) "ac" else "light"
        DevicePicker(
            title = if (current.sensor) "${current.item.name} · room temperature" else current.item.name,
            kind = kind,
            current = if (current.sensor) current.item.sensor else current.item.link,
            used = editor?.usedDevices.orEmpty(),
            devices = devices.filter { !current.sensor || it.service != "smartlife" || current.item.link?.service == "smartlife" },
            close = { picking = null },
        ) { device ->
            model.editPlan { if (current.sensor) it.setSensor(current.item.id, device?.raw) else it.link(current.item.id, device?.raw) }
            model.web.haptic("success")
            picking = null
        }
        return
    }
    if (adding) {
        BackHandler { adding = false }
        AddDevicesScreen(model, devices, editor?.usedDevices.orEmpty(), editor?.rooms.orEmpty()) { adding = false }
        return
    }

    Screen("Devices", onBack = back, trailing = {
        if (loading) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), color = Palette.muted, strokeWidth = 2.dp)
        else if (model.hasAnyConnection) TextLink("Refresh", color = Palette.accent) { load() }
    }) {
        if (!model.hasAnyConnection) {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_link_2, Palette.warm)
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nothing connected yet", style = Type.title)
                        Text("Connect Smart Life, Home Assistant or Philips Hue in Settings first; their devices then appear here to choose from.",
                            style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
        } else if (errors.isNotEmpty()) {
            Problem(errors.entries.sortedBy { it.key }.joinToString("\n") { "${serviceTitle(it.key)}: ${it.value}" })
        }
        if (editor != null) {
            for (room in editor.rooms) {
                val items = editor.items.filter { it.room == room.id }
                if (items.isEmpty()) continue
                Section(room.name) {
                    Card {
                        items.forEachIndexed { i, item ->
                            if (i > 0) RowDivider()
                            ItemRow(item, devices.firstOrNull { it.ref == item.link }) { picking = Picking(item, false) }
                            if (item.kind == DeviceKind.AC) {
                                val sensor = devices.firstOrNull { it.ref == item.sensor }
                                Row(
                                    Modifier.fillMaxWidth().clickable { picking = Picking(item, true) }.heightIn(min = 44.dp)
                                        .padding(start = 66.dp, end = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Room temperature", Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = Palette.muted))
                                    Text(sensor?.name ?: if (item.sensor != null) "Linked" else "From the AC",
                                        style = TextStyle(fontSize = 14.sp, color = if (item.sensor != null) Palette.text else Palette.faint))
                                    Chevron()
                                }
                            }
                        }
                    }
                }
            }
            if (editor.items.isEmpty()) {
                Card(padding = 16.dp) {
                    Text("This home has no lamps or air conditioners yet. Add your devices to a room, or draw them in the editor.", style = Type.body)
                }
            }
        }
        if (model.hasAnyConnection) PrimaryButton("Add devices to a room", icon = R.drawable.lucide_plus, enabled = loaded) { adding = true }
        Footnote(when {
            model.activePlan != "custom" -> "Linking a device turns this demo into your own home, saved on this phone."
            model.macName != null -> "Changes are saved at once and reach ${model.macName}."
            else -> "Changes are saved at once."
        })
    }
}

@Composable
private fun ItemRow(item: PlanEditor.Item, device: RealDevice?, onClick: () -> Unit) {
    val link = item.link
    val icon = if (item.kind == DeviceKind.AC) R.drawable.lucide_air_vent else R.drawable.lucide_lamp_ceiling
    val tint = link?.let { serviceTint(it.service) } ?: Palette.faint
    val detail = when {
        link == null -> "Simulated"
        device == null -> serviceTitle(link.service)
        !device.online -> "${device.name} · ${serviceTitle(link.service)} · offline"
        else -> "${device.name} · ${serviceTitle(link.service)}"
    }
    ListRow(icon, item.name, detail, tint, onClick, status = if (link != null) (if (device?.online == false) Palette.red else Palette.green) else null) { Chevron() }
}

/** Every device of the right kind, grouped by platform, plus "None". */
@Composable
private fun DevicePicker(
    title: String, kind: String, current: PlanEditor.DeviceRef?, used: Map<PlanEditor.DeviceRef, String>,
    devices: List<RealDevice>, close: () -> Unit, choose: (RealDevice?) -> Unit,
) {
    Screen(title, onBack = close) {
        Card {
            ListRow(R.drawable.lucide_unlink, if (kind == "sensor") "From the air conditioner" else "None — simulated",
                if (kind == "sensor") "The AC's own reading, or a simulated one" else "Shown in the 3D view, controls nothing",
                Palette.faint, onClick = { choose(null) }) { Check(current == null) }
        }
        val candidates = devices.filter { it.kind == kind }
        if (candidates.isEmpty()) Footnote(if (kind == "sensor") "No temperature sensors on the connected platforms." else "No ${if (kind == "ac") "air conditioners" else "lights or plugs"} on the connected platforms.")
        for ((service, list) in candidates.groupBy { it.service }) {
            Section(serviceTitle(service)) {
                Card {
                    list.forEachIndexed { i, device ->
                        if (i > 0) RowDivider()
                        val usedBy = used[device.ref]?.takeIf { device.ref != current }
                        val detail = listOfNotNull(device.detail.ifEmpty { null }, usedBy?.let { "used by $it" }, if (!device.online) "offline" else null).joinToString(" · ")
                        ListRow(serviceIcon(service), device.name, detail.ifEmpty { null }, if (device.online) serviceTint(service) else Palette.faint,
                            onClick = { choose(device) }) { Check(device.ref == current) }
                    }
                }
            }
        }
    }
}

/** Puts several real devices into a room at once, already linked. */
@Composable
private fun AddDevicesScreen(
    model: AppModel, devices: List<RealDevice>, used: Map<PlanEditor.DeviceRef, String>, rooms: List<PlanEditor.Room>, close: () -> Unit,
) {
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var room by remember { mutableStateOf(rooms.firstOrNull()?.id ?: NEW_ROOM) }
    var roomName by remember { mutableStateOf("") }
    val candidates = devices.filter { it.kind != "sensor" && it.ref !in used }
    Screen("Add devices", onBack = close) {
        Section("Room") {
            Card {
                rooms.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    ListRow(R.drawable.lucide_square_dashed, r.name, null, Palette.accent, onClick = { room = r.id }) { Check(room == r.id) }
                }
                if (rooms.isNotEmpty()) RowDivider()
                ListRow(R.drawable.lucide_house_plus, "New room…", null, Palette.warm, onClick = { room = NEW_ROOM }) { Check(room == NEW_ROOM) }
            }
            if (room == NEW_ROOM) Card(padding = 16.dp) { InputField("Name of the room", roomName, { roomName = it }, "Kitchen") }
        }
        Section("Devices") {
            if (candidates.isEmpty()) Footnote("Every device of the connected platforms is in the plan already.")
            else Card {
                candidates.forEachIndexed { i, device ->
                    if (i > 0) RowDivider()
                    val key = "${device.service}|${device.deviceId}"
                    ListRow(if (device.kind == "ac") R.drawable.lucide_air_vent else R.drawable.lucide_lightbulb, device.name,
                        listOf(serviceTitle(device.service), device.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                        serviceTint(device.service), onClick = { chosen = if (key in chosen) chosen - key else chosen + key }) { Check(key in chosen) }
                }
            }
            Footnote("They are spread over the room; drag them into place later in the editor.")
        }
        PrimaryButton(
            if (chosen.isEmpty()) "Add" else "Add ${chosen.size}",
            enabled = chosen.isNotEmpty() && (room != NEW_ROOM || roomName.isNotBlank()),
        ) {
            val picked = candidates.filter { "${it.service}|${it.deviceId}" in chosen }.map { it.raw }
            model.editPlan { editor ->
                val target = if (room == NEW_ROOM) editor.addRoom(roomName.trim()) else room
                editor.add(picked, target)
            }
            model.web.haptic("success")
            close()
        }
    }
}

private const val NEW_ROOM = "\u0000new"
