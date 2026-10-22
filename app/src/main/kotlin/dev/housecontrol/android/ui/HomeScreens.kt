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
        Text("Welcome to House Control", Modifier.padding(top = 20.dp),
            style = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.Center))
        Text("Your home as a live 3D model, in your pocket.", Modifier.padding(top = 6.dp),
            style = TextStyle(fontSize = 16.sp, color = Palette.muted, textAlign = TextAlign.Center))
        Column(Modifier.padding(vertical = 34.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Feature(R.drawable.lucide_pointer, Palette.warm, "Tap a lamp, drag to dim",
                "Lights, colours, air conditioners and scenes, right on the floor plan.")
            Feature(R.drawable.lucide_pencil_ruler, Palette.accent, "Draw it here or on your Mac",
                "Rooms, lamps and ACs, on this phone. Pair a Mac any time: both stay in sync.")
            Feature(R.drawable.lucide_lightbulb, Palette.green, "Your real devices",
                "Smart Life, Home Assistant and Philips Hue. Until you connect one, everything is simulated.")
        }
        PrimaryButton("Set up my home", icon = R.drawable.lucide_house) { model.finishWelcome(AppModel.WelcomeChoice.SetUpHere) }
        Spacer(Modifier.size(10.dp))
        SecondaryButton("Pair with my Mac", icon = R.drawable.lucide_laptop) { model.finishWelcome(AppModel.WelcomeChoice.Pair) }
        Spacer(Modifier.size(4.dp))
        TextLink("Explore the demo home", color = Palette.muted) { model.finishWelcome(AppModel.WelcomeChoice.Demo) }
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
    var name by remember { mutableStateOf("My home") }
    var template by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    val templates = listOf(
        Triple<String?, String, String>(null, "One room", "Draw the rest yourself, or add your devices to it"),
        Triple("studio", "Studio layout", "3 rooms with furniture, to adapt"),
        Triple("home", "Apartment layout", "5 rooms with furniture, to adapt"),
    )
    fun create() = model.createHome(name.trim(), template)

    Screen("New home", onClose = close, closeLabel = "Cancel") {
        Section("Name") {
            Card(padding = 16.dp) { InputField("What do you call it?", name, { name = it }, "My home") }
        }
        Section("Start from") {
            Card {
                templates.forEachIndexed { i, (id, title, detail) ->
                    if (i > 0) RowDivider()
                    ListRow(
                        when (id) { null -> R.drawable.lucide_square_dashed; "studio" -> R.drawable.lucide_layout_grid; else -> R.drawable.lucide_building_2 },
                        title, detail, if (id == null) Palette.warm else Palette.accent, onClick = { template = id },
                    ) { Check(template == id) }
                }
            }
            Footnote("The editor opens next: drag rooms and lamps with a finger, pinch to zoom. Lamps and air conditioners stay simulated " +
                "until you link them to your real devices.")
        }
        PrimaryButton("Create and edit", icon = R.drawable.lucide_pencil_ruler, enabled = name.isNotBlank()) {
            if (model.hasCustomPlan) {
                confirm = Confirmation("Replace ${model.customPlanName ?: "your home"}?",
                    if (model.macName == null) "This phone keeps one home of your own. Export the current one from the editor first if you want to keep it."
                    else "This phone keeps one home of your own, and ${model.macName} gets the new one too.", "Replace") { create() }
            } else create()
        }
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

/** Which scenes, lamps and "all lights off" the app icon's menu offers. */
@Composable
fun ShortcutsScreen(model: AppModel, back: () -> Unit) {
    val quick = model.quickActions
    Screen("App shortcuts", onBack = back) {
        if (quick.options.isEmpty()) {
            Footnote("Open the home once and its scenes and lamps appear here.")
        }
        val active = quick.active.map { it.id }.toSet()
        for ((title, prefix) in listOf("Scenes" to "scene:", "Lights" to "")) {
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
        Footnote("Up to four. Press and hold the House Control icon on the home screen to use them; drag one out to keep it on the home screen.")
    }
}

