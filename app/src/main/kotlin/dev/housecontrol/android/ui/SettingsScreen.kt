package dev.housecontrol.android.ui

import android.content.Context
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
import androidx.compose.ui.res.stringResource
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
    Screen(stringResource(R.string.settings_title), onClose = close) {
        HomeSection(model, open)
        ShortcutsSection(model, open)
        MacSection(model, open) { confirm = it }
        if (model.hub.cloud != null) SmartLifeSection(model, open) { confirm = it }
        ConnectionsSection(model, open)
        AboutSection()
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
    model.backupToRestore?.let { data ->
        // The run holds the backup itself: the dialog dismisses before it runs.
        ConfirmDialog(
            Confirmation(
                stringResource(R.string.backup_restore_title),
                stringResource(R.string.backup_restore_message),
                stringResource(R.string.backup_restore_action),
            ) { model.restoreBackup(data) }
        ) { model.cancelRestoreBackup() }
    }
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
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.action_cancel), color = Palette.accent) } },
    )
}

@Composable
private fun HomeSection(model: AppModel, open: (Route) -> Unit) {
    Section(stringResource(R.string.settings_section_home)) {
        Card {
            if (model.hasCustomPlan) {
                val devices = when (model.linkedDevices) {
                    0 -> stringResource(R.string.home_devices_linked_zero)
                    1 -> stringResource(R.string.home_devices_linked_one)
                    else -> stringResource(R.string.home_devices_linked_many, model.linkedDevices)
                }
                PlanRow(model, "custom", model.customPlanName ?: stringResource(R.string.home_my_home), if (model.activePlan == "custom") devices else stringResource(R.string.home_your_home),
                    R.drawable.lucide_house, Palette.warm)
                RowDivider()
            }
            PlanRow(model, "home", stringResource(R.string.home_plan_apartment), stringResource(R.string.home_plan_apartment_detail), R.drawable.lucide_building_2, Palette.accent)
            RowDivider()
            PlanRow(model, "studio", stringResource(R.string.home_plan_studio), stringResource(R.string.home_plan_studio_detail), R.drawable.lucide_layout_grid, Palette.accent)
            RowDivider()
            PlanRow(model, "house", stringResource(R.string.home_plan_house), stringResource(R.string.home_plan_house_detail), R.drawable.lucide_house, Palette.accent)
        }
        Card {
            LinkRow(R.drawable.lucide_pencil_ruler, stringResource(R.string.settings_edit_floor_plan), stringResource(R.string.settings_edit_floor_plan_detail)) { model.openEditor() }
            RowDivider()
            LinkRow(R.drawable.lucide_link_2, stringResource(R.string.devices_title),
                if (model.linkedDevices == 0) stringResource(R.string.settings_devices_link_prompt) else stringResource(R.string.settings_devices_linked_count, model.linkedDevices),
                Palette.green) { open(Route.Devices) }
        }
        Card {
            LinkRow(R.drawable.lucide_download, stringResource(R.string.backup_export_title), stringResource(R.string.backup_export_detail)) { model.requestBackup() }
            RowDivider()
            LinkRow(R.drawable.lucide_share_2, stringResource(R.string.backup_share_action_title), stringResource(R.string.backup_share_action_detail)) { model.requestBackupShare() }
        }
        Footnote(stringResource(R.string.backup_footnote))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.action_new_home), Modifier.weight(1f), R.drawable.lucide_plus) { model.showNewHome = true }
            SecondaryButton(stringResource(R.string.action_import_file), Modifier.weight(1f), R.drawable.lucide_download) { model.requestImport() }
        }
        if (!model.hasCustomPlan) {
            Footnote(stringResource(R.string.home_footnote))
        }
    }
}

@Composable
private fun PlanRow(model: AppModel, id: String, name: String, detail: String, icon: Int, tint: androidx.compose.ui.graphics.Color) {
    ListRow(icon, name, detail, tint, onClick = { model.selectPlan(id) }) { Check(model.activePlan == id) }
}

@Composable
private fun ShortcutsSection(model: AppModel, open: (Route) -> Unit) {
    Section(stringResource(R.string.settings_section_shortcuts)) {
        Card {
            val names = model.quickActions.active.joinToString(" · ") { it.title }.ifEmpty { stringResource(R.string.shortcuts_none) }
            LinkRow(R.drawable.lucide_pointer, stringResource(R.string.shortcuts_title), names, Palette.warm) { open(Route.Shortcuts) }
        }
        Footnote(stringResource(R.string.shortcuts_footnote))
    }
}

@Composable
private fun MacSection(model: AppModel, open: (Route) -> Unit, confirm: (Confirmation) -> Unit) {
    val context = LocalContext.current
    Section(stringResource(R.string.settings_section_mac)) {
        val name = model.macName
        if (name != null) {
            // Read in the composable scope: a button's callback is a plain lambda, where a
            // resource cannot be looked up any more.
            val unpairTitle = stringResource(R.string.confirm_unpair_title, name)
            val unpairMessage = stringResource(R.string.confirm_unpair_message)
            val unpairAction = stringResource(R.string.action_unpair)
            val status = model.syncStatus
            Card {
                ListRow(R.drawable.lucide_laptop, name, syncText(context, model), Palette.accent, status = syncColor(status))
                if (status is SyncClient.Status.Failed) {
                    RowDivider()
                    Text(status.reason, Modifier.fillMaxWidth().padding(14.dp), style = TextStyle(fontSize = 13.5.sp, color = Palette.red))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(stringResource(R.string.action_sync_now), Modifier.weight(1f), R.drawable.lucide_refresh_cw) { model.syncNow() }
                SecondaryButton(stringResource(R.string.action_unpair), Modifier.width(120.dp), tint = Palette.red) {
                    confirm(Confirmation(unpairTitle, unpairMessage, unpairAction) {
                        model.unpair()
                    })
                }
            }
            Footnote(stringResource(R.string.mac_footnote_synced))
        } else {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_laptop, Palette.accent)
                    Spacer(Modifier.width(14.dp))
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.mac_optional_title), style = Type.title)
                        Text(stringResource(R.string.mac_optional_detail), style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
            PrimaryButton(stringResource(R.string.action_pair_mac), icon = R.drawable.lucide_scan_qr_code) { open(Route.Pairing) }
        }
    }
}

private fun syncColor(status: SyncClient.Status) = when (status) {
    SyncClient.Status.Connected -> Palette.green
    is SyncClient.Status.Failed -> Palette.red
    else -> Palette.warm
}

private fun syncText(context: Context, model: AppModel): String = when (model.syncStatus) {
    SyncClient.Status.Connected -> context.getString(R.string.sync_connected)
    SyncClient.Status.Connecting -> context.getString(R.string.sync_connecting)
    is SyncClient.Status.Failed -> context.getString(R.string.sync_not_syncing)
    else -> model.lastSync?.let { context.getString(R.string.sync_last_synced, DateUtils.getRelativeTimeSpanString(it).toString().lowercase()) }
        ?: context.getString(R.string.sync_searching)
}

@Composable
private fun SmartLifeSection(model: AppModel, open: (Route) -> Unit, confirm: (Confirmation) -> Unit) {
    Section(stringResource(R.string.service_smart_life)) {
        if (model.smartLifeConnected) {
            // Read in the composable scope: a button's callback is a plain lambda, where a
            // resource cannot be looked up any more.
            val signOutTitle = stringResource(R.string.confirm_signout_title)
            val signOutMessage = stringResource(R.string.confirm_signout_message)
            val signOutAction = stringResource(R.string.action_sign_out)
            Card {
                ListRow(R.drawable.lucide_shield_check, model.smartLifeAccount.ifEmpty { stringResource(R.string.smartlife_signed_in) },
                    when (model.linkedDevices) {
                        0 -> stringResource(R.string.smartlife_no_devices)
                        1 -> stringResource(R.string.smartlife_controlling_one)
                        else -> stringResource(R.string.smartlife_controlling_many, model.linkedDevices)
                    }, Palette.green)
            }
            SecondaryButton(stringResource(R.string.action_sign_out), tint = Palette.red) {
                confirm(Confirmation(signOutTitle, signOutMessage, signOutAction) {
                    model.signOut()
                })
            }
        } else {
            Card(padding = 16.dp) {
                Row(verticalAlignment = Alignment.Top) {
                    IconBadge(R.drawable.lucide_plug, Palette.smartLife)
                    Spacer(Modifier.width(14.dp))
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.smartlife_not_signed_in), style = Type.title)
                        Text(stringResource(R.string.smartlife_not_signed_in_detail), style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                    }
                }
            }
            SecondaryButton(stringResource(R.string.action_connect_smartlife), icon = R.drawable.lucide_link_2) { open(Route.SmartLife) }
        }
    }
}

@Composable
private fun ConnectionsSection(model: AppModel, open: (Route) -> Unit) {
    Section(stringResource(R.string.settings_section_connections)) {
        Card {
            LinkRow(R.drawable.lucide_house_wifi, stringResource(R.string.service_home_assistant), model.homeAssistantName ?: stringResource(R.string.conn_connect_server), Palette.homeAssistant,
                status = if (model.homeAssistantName != null) Palette.green else null) { open(Route.HomeAssistant) }
            RowDivider()
            LinkRow(R.drawable.lucide_lightbulb, stringResource(R.string.service_philips_hue), model.hueName ?: stringResource(R.string.conn_pair_hue_bridge), Palette.hue,
                status = if (model.hueName != null) Palette.green else null) { open(Route.Hue) }
        }
        Footnote(stringResource(R.string.connections_footnote))
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    fun browse(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    Section(stringResource(R.string.settings_section_about)) {
        Card {
            InfoRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            RowDivider()
            InfoRow(stringResource(R.string.about_privacy), stringResource(R.string.about_no_account))
        }
        Footnote(stringResource(R.string.about_footnote))
        Card {
            LinkRow(R.drawable.lucide_globe, stringResource(R.string.about_website), stringResource(R.string.about_website_detail), external = true) { browse(About.WEBSITE) }
            RowDivider()
            LinkRow(R.drawable.lucide_shield_check, stringResource(R.string.about_privacy_policy), stringResource(R.string.about_nothing_collected), Palette.green, external = true) { browse(About.PRIVACY) }
            RowDivider()
            LinkRow(R.drawable.lucide_circle_question_mark, stringResource(R.string.about_support), stringResource(R.string.about_support_detail), Palette.warm, external = true) { browse(About.SUPPORT) }
        }
    }
    Section(stringResource(R.string.settings_section_developer)) {
        Card {
            LinkRow(R.drawable.lucide_at_sign, stringResource(R.string.about_dev_on_x, About.DEVELOPER), "@0xProbabillity", Palette.text, external = true) { browse(About.X) }
            RowDivider()
            LinkRow(R.drawable.lucide_code_xml, stringResource(R.string.about_dev_on_github, About.DEVELOPER), "github.com/0xProbabillity", Palette.text, external = true) {
                browse(About.GITHUB)
            }
            RowDivider()
            LinkRow(R.drawable.lucide_package, stringResource(R.string.about_source_code), stringResource(R.string.about_open_source), Palette.text, external = true) { browse(About.SOURCE) }
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
