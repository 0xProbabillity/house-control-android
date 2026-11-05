package dev.housecontrol.android.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

fun serviceTitle(context: Context, service: String) = when (service) {
    "smartlife" -> context.getString(R.string.service_smart_life)
    "homeassistant" -> context.getString(R.string.service_home_assistant)
    "hue" -> context.getString(R.string.service_philips_hue)
    else -> context.getString(R.string.service_apple_home)
}

/** What a picker is choosing: the device of a lamp or AC, or an AC's thermometer. */
private data class Picking(val item: PlanEditor.Item, val sensor: Boolean)

/**
 * Every lamp and AC of the plan and the real device it is linked to; tapping one picks its
 * device from the list of everything the connected platforms have.
 */
@Composable
fun DevicesScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
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
            title = if (current.sensor) stringResource(R.string.devices_picker_sensor_title, current.item.name) else current.item.name,
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

    Screen(stringResource(R.string.devices_title), onBack = back, trailing = {
        if (loading) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), color = Palette.muted, strokeWidth = 2.dp)
        else if (model.hasAnyConnection) TextLink(stringResource(R.string.action_refresh), color = Palette.accent) { load() }
    }) {
        if (!model.hasAnyConnection) {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_link_2, Palette.warm)
                    Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.devices_nothing_connected_title), style = Type.title)
                        Text(stringResource(R.string.devices_nothing_connected_detail),
                            style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
        } else if (errors.isNotEmpty()) {
            Problem(errors.entries.sortedBy { it.key }.joinToString("\n") { "${serviceTitle(context, it.key)}: ${it.value}" })
        }
        if (editor != null) {
            for (room in editor.rooms) {
                val items = editor.items.filter { it.room == room.id }
                if (items.isEmpty()) continue
                Section(room.title) {
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
                                    Text(stringResource(R.string.devices_room_temperature), Modifier.weight(1f), style = TextStyle(fontSize = 14.sp, color = Palette.muted))
                                    Text(sensor?.name ?: if (item.sensor != null) stringResource(R.string.devices_linked) else stringResource(R.string.devices_from_the_ac),
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
                    Text(stringResource(R.string.devices_empty_hint), style = Type.body)
                }
            }
        }
        if (model.hasAnyConnection) PrimaryButton(stringResource(R.string.action_add_devices), icon = R.drawable.lucide_plus, enabled = loaded) { adding = true }
        Footnote(when {
            model.activePlan != "custom" -> stringResource(R.string.devices_footnote_demo)
            model.macName != null -> stringResource(R.string.devices_footnote_mac, model.macName)
            else -> stringResource(R.string.devices_footnote_local)
        })
    }
}

@Composable
private fun ItemRow(item: PlanEditor.Item, device: RealDevice?, onClick: () -> Unit) {
    val context = LocalContext.current
    val link = item.link
    val icon = if (item.kind == DeviceKind.AC) R.drawable.lucide_air_vent else R.drawable.lucide_lamp_ceiling
    val tint = link?.let { serviceTint(it.service) } ?: Palette.faint
    val detail = when {
        link == null -> stringResource(R.string.devices_simulated)
        device == null -> serviceTitle(context, link.service)
        !device.online -> stringResource(R.string.devices_detail_offline, device.name, serviceTitle(context, link.service))
        else -> stringResource(R.string.devices_detail_online, device.name, serviceTitle(context, link.service))
    }
    ListRow(icon, item.name, detail, tint, onClick, status = if (link != null) (if (device?.online == false) Palette.red else Palette.green) else null) { Chevron() }
}

/** Every device of the right kind, grouped by platform, plus "None". */
@Composable
private fun DevicePicker(
    title: String, kind: String, current: PlanEditor.DeviceRef?, used: Map<PlanEditor.DeviceRef, String>,
    devices: List<RealDevice>, close: () -> Unit, choose: (RealDevice?) -> Unit,
) {
    val context = LocalContext.current
    Screen(title, onBack = close) {
        Card {
            ListRow(R.drawable.lucide_unlink, if (kind == "sensor") stringResource(R.string.devices_from_ac) else stringResource(R.string.devices_none_simulated),
                if (kind == "sensor") stringResource(R.string.devices_ac_own_reading) else stringResource(R.string.devices_shown_3d),
                Palette.faint, onClick = { choose(null) }) { Check(current == null) }
        }
        val candidates = devices.filter { it.kind == kind }
        if (candidates.isEmpty()) Footnote(when (kind) {
            "sensor" -> stringResource(R.string.devices_no_sensors)
            "ac" -> stringResource(R.string.devices_no_ac)
            else -> stringResource(R.string.devices_no_lights)
        })
        for ((service, list) in candidates.groupBy { it.service }) {
            Section(serviceTitle(context, service)) {
                Card {
                    list.forEachIndexed { i, device ->
                        if (i > 0) RowDivider()
                        val usedBy = used[device.ref]?.takeIf { device.ref != current }
                        val detail = listOfNotNull(device.detail.ifEmpty { null }, usedBy?.let { context.getString(R.string.devices_used_by, it) }, if (!device.online) context.getString(R.string.devices_offline) else null).joinToString(" · ")
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
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(setOf<String>()) }
    var room by remember { mutableStateOf(rooms.firstOrNull()?.id ?: NEW_ROOM) }
    var roomName by remember { mutableStateOf("") }
    val candidates = devices.filter { it.kind != "sensor" && it.ref !in used }
    Screen(stringResource(R.string.add_devices_title), onBack = close) {
        Section(stringResource(R.string.devices_section_room)) {
            Card {
                rooms.forEachIndexed { i, r ->
                    if (i > 0) RowDivider()
                    ListRow(R.drawable.lucide_square_dashed, r.title, null, Palette.accent, onClick = { room = r.id }) { Check(room == r.id) }
                }
                if (rooms.isNotEmpty()) RowDivider()
                ListRow(R.drawable.lucide_house_plus, stringResource(R.string.add_devices_new_room), null, Palette.warm, onClick = { room = NEW_ROOM }) { Check(room == NEW_ROOM) }
            }
            if (room == NEW_ROOM) Card(padding = 16.dp) { InputField(stringResource(R.string.add_devices_room_name_label), roomName, { roomName = it }, stringResource(R.string.add_devices_room_placeholder)) }
        }
        Section(stringResource(R.string.devices_title)) {
            if (candidates.isEmpty()) Footnote(stringResource(R.string.add_devices_all_in_plan))
            else Card {
                candidates.forEachIndexed { i, device ->
                    if (i > 0) RowDivider()
                    val key = "${device.service}|${device.deviceId}"
                    ListRow(if (device.kind == "ac") R.drawable.lucide_air_vent else R.drawable.lucide_lightbulb, device.name,
                        listOf(serviceTitle(context, device.service), device.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                        serviceTint(device.service), onClick = { chosen = if (key in chosen) chosen - key else chosen + key }) { Check(key in chosen) }
                }
            }
            Footnote(stringResource(R.string.add_devices_footnote))
        }
        PrimaryButton(
            if (chosen.isEmpty()) stringResource(R.string.action_add) else stringResource(R.string.action_add_count, chosen.size),
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
