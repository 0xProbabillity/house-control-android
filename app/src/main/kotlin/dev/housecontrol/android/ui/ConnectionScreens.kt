package dev.housecontrol.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.R
import dev.housecontrol.android.Route
import dev.housecontrol.android.core.objects
import dev.housecontrol.android.core.readable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** "Where is it?" help: a title and text with **bold** parts. */
@Composable
fun HelpCard(title: String, text: String) {
    Card(padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(R.drawable.lucide_circle_question_mark), null,
                    Modifier.size(17.dp), tint = Palette.text)
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Text(title, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.text))
            }
            Text(bold(text), style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
        }
    }
}

/** Markdown's **bold**, and nothing else. */
fun bold(text: String) = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Palette.text)) { append(part) } else append(part)
    }
}

/** Connects this phone to a Home Assistant server: found on the Wi-Fi by itself or typed in, plus a long-lived access token. */
@Composable
fun HomeAssistantScreen(model: AppModel, insideEditor: Boolean, open: (Route) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaultServerName = stringResource(R.string.service_home_assistant)
    // Read in the composable scope: a button's callback is a plain lambda, where a resource
    // cannot be looked up any more.
    val disconnectTitle = stringResource(R.string.confirm_disconnect_ha_title)
    val disconnectMessage = stringResource(R.string.confirm_disconnect_ha_message)
    val disconnectAction = stringResource(R.string.action_disconnect)
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(listOf<JSONObject>()) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    suspend fun scan() {
        scanning = true
        found = model.discovery.homeAssistants().objects()
        scanning = false
        if (url.isEmpty() && found.size == 1) url = found[0].optString("url")
    }
    fun connect() {
        busy = true
        problem = null
        scope.launch {
            try {
                model.connectHomeAssistant(url, token)
                token = ""
            } catch (error: Exception) {
                problem = error.readable
                model.web.haptic("error")
            }
            busy = false
        }
    }
    LaunchedEffect(Unit) { if (model.homeAssistantName == null) scan() }

    Screen(stringResource(R.string.service_home_assistant), onBack = back) {
        val name = model.homeAssistantName
        if (name != null) {
            Section(stringResource(R.string.ha_section_server)) {
                Card {
                    val info = model.homeAssistantInfo
                    val version = info.optString("version")
                    val versionLabel = if (version.isNotEmpty()) stringResource(R.string.ha_version, version) else null
                    val line = listOfNotNull(info.optString("url"), versionLabel).joinToString(" · ")
                    ListRow(R.drawable.lucide_house_wifi, name, line, Palette.homeAssistant, status = Palette.green)
                }
                if (!insideEditor) PrimaryButton(stringResource(R.string.action_link_devices), icon = R.drawable.lucide_link_2) { open(Route.Devices) }
                SecondaryButton(stringResource(R.string.action_disconnect), tint = Palette.red) {
                    confirm = Confirmation(disconnectTitle, disconnectMessage,
                        disconnectAction) { model.disconnect("homeassistant") }
                }
                Footnote(stringResource(R.string.ha_footnote))
            }
        } else {
            Section(stringResource(R.string.ha_section_on_wifi)) {
                Card {
                    when {
                        scanning -> Busy(stringResource(R.string.ha_looking))
                        found.isEmpty() -> Text(stringResource(R.string.ha_none_found), Modifier.padding(16.dp), style = Type.body)
                        else -> found.forEachIndexed { i, server ->
                            if (i > 0) RowDivider()
                            val address = server.optString("url")
                            ListRow(R.drawable.lucide_house_wifi, server.optString("name", defaultServerName), address, Palette.homeAssistant,
                                onClick = { url = address }) { Check(url == address) }
                        }
                    }
                }
                if (!scanning) TextLink(stringResource(R.string.action_look_again), R.drawable.lucide_radar) { scope.launch { scan() } }
            }
            Section(stringResource(R.string.action_connect)) {
                Card(padding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        InputField(stringResource(R.string.ha_address_label), url, { url = it }, "http://homeassistant.local:8123", keyboard = KeyboardType.Uri, imeAction = ImeAction.Next)
                        InputField(stringResource(R.string.ha_token_label), token, { token = it }, stringResource(R.string.ha_token_placeholder), secret = true, imeAction = ImeAction.Go,
                            onSubmit = { if (url.isNotBlank() && token.isNotBlank()) connect() })
                    }
                }
                problem?.let { Problem(it) }
                PrimaryButton(stringResource(R.string.action_connect), enabled = url.isNotBlank() && token.isNotBlank(), busy = busy) { connect() }
                HelpCard(stringResource(R.string.ha_token_help_title), stringResource(R.string.ha_token_help_text))
                Footnote(stringResource(R.string.ha_token_footnote))
            }
        }
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

/** Pairs this phone with a Philips Hue Bridge: pick it, press its round button. */
@Composable
fun HueScreen(model: AppModel, insideEditor: Boolean, open: (Route) -> Unit, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaultBridgeName = stringResource(R.string.service_hue_bridge)
    val buttonTimeout = stringResource(R.string.hue_button_timeout)
    // Read in the composable scope: a button's callback is a plain lambda, where a resource
    // cannot be looked up any more.
    val disconnectTitle = stringResource(R.string.confirm_disconnect_hue_title)
    val disconnectMessage = stringResource(R.string.confirm_disconnect_hue_message)
    val disconnectAction = stringResource(R.string.action_disconnect)
    var found by remember { mutableStateOf(listOf<JSONObject>()) }
    var scanning by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf<JSONObject?>(null) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    suspend fun scan() {
        scanning = true
        found = model.discovery.hueBridges().objects()
        scanning = false
    }
    fun start(bridge: JSONObject) {
        problem = null
        pairing = bridge
    }
    LaunchedEffect(Unit) { if (model.hueName == null) scan() }
    // While waiting for the button: ask the bridge every two seconds, for a minute.
    LaunchedEffect(pairing) {
        val bridge = pairing ?: return@LaunchedEffect
        for (left in 60 downTo 0 step 2) {
            secondsLeft = left
            try {
                if (model.pairHue(bridge.optString("host"), bridge.optString("bridgeId").ifEmpty { null }, bridge.optString("name").ifEmpty { null })) {
                    pairing = null
                    return@LaunchedEffect
                }
            } catch (error: Exception) {
                problem = error.readable
                pairing = null
                model.web.haptic("error")
                return@LaunchedEffect
            }
            delay(2000)
        }
        problem = buttonTimeout
        pairing = null
    }

    Screen(stringResource(R.string.service_philips_hue), onBack = back) {
        val name = model.hueName
        val waiting = pairing
        when {
            name != null -> Section(stringResource(R.string.hue_section_bridge)) {
                Card { ListRow(R.drawable.lucide_lightbulb, name, model.hueInfo.optString("host"), Palette.hue, status = Palette.green) }
                if (!insideEditor) PrimaryButton(stringResource(R.string.action_link_devices), icon = R.drawable.lucide_link_2) { open(Route.Devices) }
                SecondaryButton(stringResource(R.string.action_disconnect), tint = Palette.red) {
                    confirm = Confirmation(disconnectTitle, disconnectMessage,
                        disconnectAction) { model.disconnect("hue") }
                }
            }
            waiting != null -> PressButton(waiting.optString("name", defaultBridgeName), secondsLeft) { pairing = null }
            else -> {
                Section(stringResource(R.string.ha_section_on_wifi)) {
                    Card {
                        when {
                            scanning -> Busy(stringResource(R.string.hue_looking))
                            found.isEmpty() -> Text(stringResource(R.string.hue_no_bridge), Modifier.padding(16.dp), style = Type.body)
                            else -> found.forEachIndexed { i, bridge ->
                                if (i > 0) RowDivider()
                                ListRow(R.drawable.lucide_lightbulb, bridge.optString("name", defaultBridgeName), bridge.optString("host"), Palette.hue) {
                                    PillButton(stringResource(R.string.action_pair_hue)) { start(bridge) }
                                }
                            }
                        }
                    }
                    problem?.let { Problem(it) }
                    if (!scanning) TextLink(stringResource(R.string.action_look_again), R.drawable.lucide_radar) { scope.launch { scan() } }
                }
                Section(stringResource(R.string.hue_section_or_address)) {
                    Card(padding = 16.dp) {
                        InputField(stringResource(R.string.hue_bridge_address_label), host, { host = it }, stringResource(R.string.hue_address_placeholder), keyboard = KeyboardType.Uri, imeAction = ImeAction.Go,
                            onSubmit = { if (host.isNotBlank()) start(JSONObject().put("host", host.trim()).put("name", "Hue Bridge")) })
                    }
                    SecondaryButton(stringResource(R.string.action_pair_hue), enabled = host.isNotBlank()) { start(JSONObject().put("host", host.trim()).put("name", "Hue Bridge")) }
                    Footnote(stringResource(R.string.hue_pair_footnote))
                }
            }
        }
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun PressButton(name: String, secondsLeft: Int, cancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).background(Palette.surfaceHigh, CircleShape))
            Box(Modifier.size(150.dp).scale(pulse.value).alpha(2 - pulse.value).border(3.dp, Palette.hue.copy(alpha = 0.7f), CircleShape))
            Box(Modifier.size(56.dp).shadow(18.dp, CircleShape, spotColor = Palette.hue).background(Color.White.copy(alpha = 0.9f), CircleShape))
        }
        Text(stringResource(R.string.hue_press_button), style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.text,
            textAlign = TextAlign.Center))
        Text(stringResource(R.string.hue_waiting, name, secondsLeft), style = TextStyle(fontSize = 15.sp, color = Palette.muted))
        SecondaryButton(stringResource(R.string.action_cancel), onClick = cancel)
    }
}
