package dev.housecontrol.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.R

/** Shown once, over the demo home that is already running behind it. */
@Composable
fun WelcomeScreen(model: AppModel) {
    Column(
        Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 10.dp)
            .wrapContentWidth().widthIn(max = 520.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(top = 36.dp).size(76.dp).shadow(22.dp, RoundedCornerShape(20.dp), spotColor = Palette.warm)
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF5C3D85), Color(0xFF21295C), Color(0xFF0D1229)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.lucide_house), null, Modifier.size(36.dp), tint = Color(0xFFFFF2DB))
        }
        Text(stringResource(R.string.welcome_title), Modifier.padding(top = 20.dp),
            style = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.Center))
        Text(stringResource(R.string.welcome_subtitle), Modifier.padding(top = 6.dp),
            style = TextStyle(fontSize = 16.sp, color = Palette.muted, textAlign = TextAlign.Center))
        Column(Modifier.padding(vertical = 34.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Feature(R.drawable.lucide_pointer, Palette.warm, stringResource(R.string.welcome_feature_tap_title),
                stringResource(R.string.welcome_feature_tap_detail))
            Feature(R.drawable.lucide_pencil_ruler, Palette.accent, stringResource(R.string.welcome_feature_draw_title),
                stringResource(R.string.welcome_feature_draw_detail))
            Feature(R.drawable.lucide_lightbulb, Palette.green, stringResource(R.string.welcome_feature_devices_title),
                stringResource(R.string.welcome_feature_devices_detail))
        }
        PrimaryButton(stringResource(R.string.action_set_up_home), icon = R.drawable.lucide_house) { model.finishWelcome(AppModel.WelcomeChoice.SetUpHere) }
        Spacer(Modifier.size(10.dp))
        SecondaryButton(stringResource(R.string.action_pair_mac), icon = R.drawable.lucide_laptop) { model.finishWelcome(AppModel.WelcomeChoice.Pair) }
        Spacer(Modifier.size(4.dp))
        TextLink(stringResource(R.string.action_explore_demo), color = Palette.muted) { model.finishWelcome(AppModel.WelcomeChoice.Demo) }
    }
}

@Composable
private fun Feature(icon: Int, tint: Color, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        IconBadge(icon, tint, 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text))
            Text(detail, style = TextStyle(fontSize = 14.5.sp, color = Palette.muted, lineHeight = 20.sp))
        }
    }
}

/** Starts the user's own home on this phone: a name and something to start drawing from. */
@Composable
fun NewHomeScreen(model: AppModel, close: () -> Unit) {
    val defaultName = stringResource(R.string.home_my_home)
    var name by remember { mutableStateOf(defaultName) }
    var template by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    // Read in the composable scope: a button's callback is a plain lambda, where a resource
    // cannot be looked up any more.
    val replaceTitle = stringResource(R.string.confirm_replace_title, model.customPlanName ?: stringResource(R.string.newhome_home_lower))
    // macName is a delegated property: a local can be smart cast, the property cannot.
    val macName = model.macName
    val replaceMessage = if (macName == null) stringResource(R.string.confirm_replace_message_no_mac)
        else stringResource(R.string.confirm_replace_message_with_mac, macName)
    val replaceAction = stringResource(R.string.action_replace)
    val templates = listOf(
        Triple<String?, String, String>(null, stringResource(R.string.newhome_template_one_room), stringResource(R.string.newhome_template_one_room_detail)),
        Triple("studio", stringResource(R.string.newhome_template_studio), stringResource(R.string.newhome_template_studio_detail)),
        Triple("home", stringResource(R.string.newhome_template_apartment), stringResource(R.string.newhome_template_apartment_detail)),
        Triple("house", stringResource(R.string.newhome_template_house), stringResource(R.string.newhome_template_house_detail)),
    )
    fun create() = model.createHome(name.trim(), template)

    Screen(stringResource(R.string.action_new_home), onClose = close, closeLabel = stringResource(R.string.action_cancel)) {
        Section(stringResource(R.string.newhome_section_name)) {
            Card(padding = 16.dp) { InputField(stringResource(R.string.newhome_name_label), name, { name = it }, stringResource(R.string.home_my_home)) }
        }
        Section(stringResource(R.string.newhome_section_start_from)) {
            Card {
                templates.forEachIndexed { i, (id, title, detail) ->
                    if (i > 0) RowDivider()
                    ListRow(
                        when (id) {
                            null -> R.drawable.lucide_square_dashed
                            "studio" -> R.drawable.lucide_layout_grid
                            "house" -> R.drawable.lucide_house
                            else -> R.drawable.lucide_building_2
                        },
                        title, detail, if (id == null) Palette.warm else Palette.accent, onClick = { template = id },
                    ) { Check(template == id) }
                }
            }
            Footnote(stringResource(R.string.newhome_footnote))
        }
        PrimaryButton(stringResource(R.string.action_create_and_edit), icon = R.drawable.lucide_pencil_ruler, enabled = name.isNotBlank()) {
            if (model.hasCustomPlan) {
                confirm = Confirmation(replaceTitle, replaceMessage, replaceAction) { create() }
            } else create()
        }
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

/** Which scenes, lamps and "all lights off" the app icon's menu offers. */
@Composable
fun ShortcutsScreen(model: AppModel, back: () -> Unit) {
    val quick = model.quickActions
    Screen(stringResource(R.string.shortcuts_title), onBack = back) {
        if (quick.options.isEmpty()) {
            Footnote(stringResource(R.string.shortcuts_empty_hint))
        }
        val active = quick.active.map { it.id }.toSet()
        for ((title, prefix) in listOf(stringResource(R.string.section_scenes) to "scene:", stringResource(R.string.section_lights) to "")) {
            val list = quick.options.filter { if (prefix.isEmpty()) !it.id.startsWith("scene:") else it.id.startsWith(prefix) }
            if (list.isEmpty()) continue
            Section(title) {
                Card {
                    list.forEachIndexed { i, option ->
                        if (i > 0) RowDivider()
                        ListRow(option.icon, option.title, null, Palette.warm, onClick = { quick.toggle(option.id) }) { Check(option.id in active) }
                    }
                }
            }
        }
        Footnote(stringResource(R.string.shortcuts_limit_footnote))
    }
}

