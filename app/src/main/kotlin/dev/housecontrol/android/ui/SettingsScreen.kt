package dev.housecontrol.android.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.BuildConfig
import dev.housecontrol.android.R
import dev.housecontrol.android.Route
import dev.housecontrol.android.sync.SyncClient

/** Who makes House Control and where to find it. */
object About {
    const val DEVELOPER = "0xProbability"
    const val WEBSITE = "https://house-control-3d.vercel.app"
    const val PRIVACY = "https://house-control-3d.vercel.app/privacy"
    const val SUPPORT = "https://house-control-3d.vercel.app/support"
    const val X = "https://x.com/0xProbabillity"
    const val GITHUB = "https://github.com/0xProbabillity"
    const val SOURCE = "https://github.com/0xProbabillity/house-control-android"
}

/** Which home is shown, the link to the Mac, Smart Life and the other connections. */
@Composable
fun SettingsScreen(model: AppModel, open: (Route) -> Unit, close: () -> Unit) {
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    Screen("Settings", onClose = close) {
        HomeSection(model, open)
        ShortcutsSection(model, open)
        MacSection(model, open) { confirm = it }
        if (model.hub.cloud != null) SmartLifeSection(model, open) { confirm = it }
        ConnectionsSection(model, open)
        AboutSection()
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

/** A question before something that cannot simply be undone. */
data class Confirmation(val title: String, val message: String, val action: String, val run: () -> Unit)

@Composable
fun ConfirmDialog(confirmation: Confirmation, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.raised,
        title = { Text(confirmation.title, color = Palette.text) },
        text = { Text(confirmation.message, color = Palette.muted) },
        confirmButton = {
            TextButton({ dismiss(); confirmation.run() }) { Text(confirmation.action, color = Palette.red) }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel", color = Palette.accent) } },
    )
}

@Composable
private fun HomeSection(model: AppModel, open: (Route) -> Unit) {
    Section("Home") {
        Card {
            if (model.hasCustomPlan) {
                val devices = when (model.linkedDevices) {
                    0 -> "No real devices linked"
                    1 -> "1 real device linked"
                    else -> "${model.linkedDevices} real devices linked"
                }
                PlanRow(model, "custom", model.customPlanName ?: "My home", if (model.activePlan == "custom") devices else "Your home",
                    R.drawable.lucide_house, Palette.warm)
                RowDivider()
            }
            PlanRow(model, "home", "Apartment", "Demo · 5 rooms, simulated devices", R.drawable.lucide_building_2, Palette.accent)
            RowDivider()
            PlanRow(model, "studio", "Studio", "Demo · 3 rooms, simulated devices", R.drawable.lucide_layout_grid, Palette.accent)
        }
        Card {
            LinkRow(R.drawable.lucide_pencil_ruler, "Edit floor plan", "Rooms, lamps, air conditioners, furniture") { model.openEditor() }
            RowDivider()
            LinkRow(R.drawable.lucide_link_2, "Devices",
                if (model.linkedDevices == 0) "Link the plan to your real devices" else "${model.linkedDevices} linked to real devices",
                Palette.green) { open(Route.Devices) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("New home", Modifier.weight(1f), R.drawable.lucide_plus) { model.showNewHome = true }
            SecondaryButton("Import file", Modifier.weight(1f), R.drawable.lucide_download) { model.requestImport() }
        }
        if (!model.hasCustomPlan) {
            Footnote("Make your own home here, or pair with House Control on a Mac and the one drawn there arrives by itself.")
        }
    }
}

@Composable
private fun PlanRow(model: AppModel, id: String, name: String, detail: String, icon: Int, tint: androidx.compose.ui.graphics.Color) {
    ListRow(icon, name, detail, tint, onClick = { model.selectPlan(id) }) { Check(model.activePlan == id) }
}

@Composable
private fun ShortcutsSection(model: AppModel, open: (Route) -> Unit) {
    Section("Shortcuts") {
        Card {
            val names = model.quickActions.active.joinToString(" · ") { it.title }.ifEmpty { "None" }
            LinkRow(R.drawable.lucide_pointer, "App shortcuts", names, Palette.warm) { open(Route.Shortcuts) }
        }
        Footnote("Press and hold the app icon for these. Widgets — your home from above, scenes, lights — and the All lights tile " +
            "are added from the home screen and Quick Settings.")
    }
}

@Composable
private fun MacSection(model: AppModel, open: (Route) -> Unit, confirm: (Confirmation) -> Unit) {
    Section("Mac") {
        val name = model.macName
        if (name != null) {
            val status = model.syncStatus
            Card {
                ListRow(R.drawable.lucide_laptop, name, syncText(model), Palette.accent, status = syncColor(status))
                if (status is SyncClient.Status.Failed) {
                    RowDivider()
                    Text(status.reason, Modifier.fillMaxWidth().padding(14.dp), style = TextStyle(fontSize = 13.5.sp, color = Palette.red))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Sync now", Modifier.weight(1f), R.drawable.lucide_refresh_cw) { model.syncNow() }
                SecondaryButton("Unpair", Modifier.width(120.dp), tint = Palette.red) {
                    confirm(Confirmation("Unpair from $name?", "The floor plan stays on this phone but no longer follows the Mac.", "Unpair") {
                        model.unpair()
                    })
                }
            }
            Footnote("Changes saved on either one reach the other while both are on the same Wi-Fi: the floor plan, device links, Home Assistant and Hue.")
        } else {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_laptop, Palette.accent)
                    Spacer(Modifier.width(14.dp))
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Optional: a Mac", style = Type.title)
                        Text("Pair with House Control on a Mac and your home — rooms, lamps, device links, connections — stays the same on both, " +
                            "whichever one you edit.", style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
            PrimaryButton("Pair with Mac", icon = R.drawable.lucide_scan_qr_code) { open(Route.Pairing) }
        }
    }
}

private fun syncColor(status: SyncClient.Status) = when (status) {
    SyncClient.Status.Connected -> Palette.green
    is SyncClient.Status.Failed -> Palette.red
    else -> Palette.warm
}

private fun syncText(model: AppModel): String = when (model.syncStatus) {
    SyncClient.Status.Connected -> "Connected · up to date"
    SyncClient.Status.Connecting -> "Connecting…"
    is SyncClient.Status.Failed -> "Not syncing"
    else -> model.lastSync?.let { "Last synced " + DateUtils.getRelativeTimeSpanString(it).toString().lowercase() }
        ?: "Looking for it on this Wi-Fi…"
}

@Composable
private fun SmartLifeSection(model: AppModel, open: (Route) -> Unit, confirm: (Confirmation) -> Unit) {
    Section("Smart Life") {
        if (model.smartLifeConnected) {
            Card {
                ListRow(R.drawable.lucide_shield_check, model.smartLifeAccount.ifEmpty { "Signed in" },
                    when (model.linkedDevices) {
                        0 -> "No devices linked in this floor plan"
                        1 -> "Controlling 1 device"
                        else -> "Controlling ${model.linkedDevices} devices"
                    }, Palette.green)
            }
            SecondaryButton("Sign out", tint = Palette.red) {
                confirm(Confirmation("Sign out of Smart Life?", "Lights and air conditioners go back to being simulated on this phone.", "Sign out") {
                    model.signOut()
                })
            }
        } else {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_plug, Palette.smartLife)
                    Spacer(Modifier.width(14.dp))
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Not signed in", style = Type.title)
                        Text("Sign in with your Smart Life or Tuya Smart account and the lamps and air conditioners linked to it in your " +
                            "floor plan become the real ones.", style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
            SecondaryButton("Connect Smart Life", icon = R.drawable.lucide_link_2) { open(Route.SmartLife) }
        }
    }
}

@Composable
private fun ConnectionsSection(model: AppModel, open: (Route) -> Unit) {
    Section("More connections") {
        Card {
            LinkRow(R.drawable.lucide_house_wifi, "Home Assistant", model.homeAssistantName ?: "Connect your server", Palette.homeAssistant,
                status = if (model.homeAssistantName != null) Palette.green else null) { open(Route.HomeAssistant) }
            RowDivider()
            LinkRow(R.drawable.lucide_lightbulb, "Philips Hue", model.hueName ?: "Pair your Hue Bridge", Palette.hue,
                status = if (model.hueName != null) Palette.green else null) { open(Route.Hue) }
        }
        Footnote("Home Assistant and Hue set up here or on your paired Mac work on both, shared over your encrypted link. " +
            "Apple Home exists only on Apple devices: add HomeKit or Matter accessories to Home Assistant to switch them here.")
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    fun browse(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Section("About") {
        Card {
            InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            RowDivider()
            InfoRow("Privacy", "No account, no analytics")
        }
        Footnote("House Control talks to your own devices: Tuya's servers for Smart Life, your Home Assistant, your Hue Bridge and " +
            "your Mac. Nothing else leaves this phone.")
        Card {
            LinkRow(R.drawable.lucide_globe, "Website", "Features, help and the Mac app", external = true) { browse(About.WEBSITE) }
            RowDivider()
            LinkRow(R.drawable.lucide_shield_check, "Privacy Policy", "Nothing is collected", Palette.green, external = true) { browse(About.PRIVACY) }
            RowDivider()
            LinkRow(R.drawable.lucide_circle_question_mark, "Support", "Questions and problems", Palette.warm, external = true) { browse(About.SUPPORT) }
        }
    }
    Section("Developer") {
        Card {
            LinkRow(R.drawable.lucide_at_sign, "${About.DEVELOPER} on X", "@0xProbabillity", Palette.text, external = true) { browse(About.X) }
            RowDivider()
            LinkRow(R.drawable.lucide_code_xml, "${About.DEVELOPER} on GitHub", "github.com/0xProbabillity", Palette.text, external = true) {
                browse(About.GITHUB)
            }
            RowDivider()
            LinkRow(R.drawable.lucide_package, "Source code", "House Control is open source", Palette.text, external = true) { browse(About.SOURCE) }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = TextStyle(fontSize = 16.sp, color = Palette.text))
        Text(value, style = TextStyle(fontSize = 15.sp, color = Palette.muted))
    }
}
