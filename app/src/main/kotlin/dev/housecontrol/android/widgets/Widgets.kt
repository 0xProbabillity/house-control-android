package dev.housecontrol.android.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.housecontrol.android.MainActivity
import dev.housecontrol.android.R
import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.core.HomeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The Home, Scenes and Lights widgets. Every tap switches the real devices without opening
// the app (WidgetBridge.perform); the plan itself opens the app.

private val text = ColorProvider(Color(0xFFF3F5FA))
private val muted = ColorProvider(Color(0x99DEE6F7))
private val warm = ColorProvider(Color(0xFFFFB46B))

private val KindKey = ActionParameters.Key<String>("kind")
private val IdKey = ActionParameters.Key<String>("id")
private val OnKey = ActionParameters.Key<Boolean>("on")

/** What a tap on a widget runs: a lamp, a scene, every light. */
class RunAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val action = HomeAction(parameters[KindKey] ?: return, parameters[IdKey], parameters[OnKey])
        withContext(Dispatchers.Main) { WidgetBridge.perform(context, action) }
    }
}

private fun run(kind: String, id: String? = null, on: Boolean? = null) = actionRunCallback<RunAction>(
    actionParametersOf(*listOfNotNull(KindKey to kind, id?.let { IdKey to it }, on?.let { OnKey to it }).toTypedArray()),
)

private fun openApp(context: Context, device: String? = null) = actionStartActivity(
    Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse(if (device == null) "housecontrol://open" else "housecontrol://open?device=$device"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

fun sceneIcon(name: String) = when (name) {
    "sun" -> R.drawable.lucide_sun
    "sofa" -> R.drawable.lucide_sofa
    "film" -> R.drawable.lucide_film
    "moon" -> R.drawable.lucide_moon
    "door" -> R.drawable.lucide_door_open
    "table" -> R.drawable.lucide_lamp_desk
    "tv" -> R.drawable.lucide_tv
    "bed" -> R.drawable.lucide_bed_double
    "leaf" -> R.drawable.lucide_leaf
    "power" -> R.drawable.lucide_power
    else -> R.drawable.lucide_sparkles
}

fun lampIcon(type: String) = when (type) {
    "ceiling" -> R.drawable.lucide_lamp_ceiling
    "pendant" -> R.drawable.lucide_lamp
    "floor" -> R.drawable.lucide_lamp_floor
    "table" -> R.drawable.lucide_lamp_desk
    "strip" -> R.drawable.lucide_sparkles
    else -> R.drawable.lucide_lightbulb
}

/** "3 lights on · 23°", or how the home rests. */
fun statusLine(snapshot: HomeSnapshot): String {
    val on = snapshot.lightsOn
    val lights = when (on) {
        0 -> "All lights off"
        1 -> "1 light on"
        else -> "$on lights on"
    }
    val ac = snapshot.acsOn.firstOrNull()?.let { " · ${it.optDouble("target").roundToInt()}°" } ?: ""
    return lights + ac
}

@Composable
private fun Empty() {
    val context = LocalContext.current
    Box(
        GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(16.dp).clickable(openApp(context)),
        contentAlignment = Alignment.Center,
    ) {
        Text("Open House Control once to show your home here.", style = TextStyle(color = muted, fontSize = 13.sp))
    }
}

@Composable
private fun SceneChip(scene: JSONObject, current: Boolean, modifier: GlanceModifier, showName: Boolean = true) {
    Row(
        modifier.background(ImageProvider(if (current) R.drawable.widget_chip_on else R.drawable.widget_chip))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(run(HomeAction.SCENE, scene.optString("id"))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (showName) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Image(ImageProvider(sceneIcon(scene.optString("icon"))), scene.optString("name"),
            GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(if (current) warm else text))
        if (showName) {
            Spacer(GlanceModifier.width(7.dp))
            Text(scene.optString("name"), maxLines = 1, style = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

// MARK: - Home

class HomeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read on every update: a running session recomposes instead of starting over.
        provideContent { WidgetStore(context).snapshot()?.let { Home(it) } ?: Empty() }
    }

    @Composable
    private fun Home(snapshot: HomeSnapshot) {
        val context = LocalContext.current
        val size = LocalSize.current
        val density = context.resources.displayMetrics.density
        val sceneRow = size.height > 150.dp
        val planSize = DpSize(size.width - 24.dp, size.height - (if (sceneRow) 92.dp else 50.dp))
        val bitmap = PlanRenderer.render(snapshot,
            min(900, max(1, (planSize.width.value * density).toInt())), min(900, max(1, (planSize.height.value * density).toInt())))
        Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(openApp(context)), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.lucide_house), null, GlanceModifier.size(15.dp), colorFilter = ColorFilter.tint(warm))
                Spacer(GlanceModifier.width(6.dp))
                Text(snapshot.name, maxLines = 1, style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight())
                Text(statusLine(snapshot), maxLines = 1, style = TextStyle(color = muted, fontSize = 12.sp))
            }
            Image(ImageProvider(bitmap), "Floor plan of ${snapshot.name}",
                GlanceModifier.fillMaxWidth().defaultWeight().padding(vertical = 4.dp).clickable(openApp(context)))
            if (sceneRow && snapshot.scenes.isNotEmpty()) {
                val fit = max(1, ((size.width.value - 24) / 96).toInt())
                Row(GlanceModifier.fillMaxWidth()) {
                    snapshot.scenes.take(fit).forEachIndexed { i, scene ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        SceneChip(scene, scene.optString("id") == snapshot.scene, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

class HomeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = HomeWidget()
}

// MARK: - Scenes

class ScenesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read on every update: a running session recomposes instead of starting over.
        provideContent { WidgetStore(context).snapshot()?.let { Scenes(it) } ?: Empty() }
    }

    @Composable
    private fun Scenes(snapshot: HomeSnapshot) {
        val size = LocalSize.current
        val columns = max(1, ((size.width.value - 20) / 120).toInt())
        val rows = max(1, ((size.height.value - 20) / 46).toInt())
        val scenes = snapshot.scenes.take(columns * rows)
        Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            scenes.chunked(columns).forEachIndexed { r, line ->
                if (r > 0) Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    line.forEachIndexed { i, scene ->
                        if (i > 0) Spacer(GlanceModifier.width(6.dp))
                        SceneChip(scene, scene.optString("id") == snapshot.scene, GlanceModifier.defaultWeight().height(40.dp))
                    }
                    repeat(columns - line.size) {
                        Spacer(GlanceModifier.width(6.dp))
                        Spacer(GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

class ScenesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ScenesWidget()
}

// MARK: - Lights

class LightsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read on every update: a running session recomposes instead of starting over.
        provideContent { WidgetStore(context).snapshot()?.let { Lights(it) } ?: Empty() }
    }

    @Composable
    private fun Lights(snapshot: HomeSnapshot) {
        val rooms = snapshot.rooms.associate { it.optString("id") to it.optString("name") }
        val anyOn = snapshot.lightsOn > 0
        Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(10.dp)) {
            Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text("Lights", style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    Text(statusLine(snapshot), maxLines = 1, style = TextStyle(color = muted, fontSize = 12.sp))
                }
                Row(
                    GlanceModifier.background(ImageProvider(R.drawable.widget_chip)).padding(horizontal = 12.dp, vertical = 7.dp)
                        .clickable(run(HomeAction.ALL_LIGHTS, on = !anyOn)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(R.drawable.lucide_power), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(if (anyOn) warm else text))
                    Spacer(GlanceModifier.width(6.dp))
                    Text(if (anyOn) "All off" else "All on", style = TextStyle(color = text, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            // As many lamps as fit; a plain column redraws reliably, unlike a collection.
            val fit = max(1, ((LocalSize.current.height.value - 64) / 52).toInt())
            Column(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (light in snapshot.lights.take(fit)) {
                    val on = light.optBoolean("on")
                    Row(
                        GlanceModifier.fillMaxWidth().background(ImageProvider(if (on) R.drawable.widget_chip_on else R.drawable.widget_chip))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clickable(run(HomeAction.LIGHT, light.optString("id"), !on)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(ImageProvider(lampIcon(light.optString("type"))), null, GlanceModifier.size(18.dp),
                            colorFilter = ColorFilter.tint(if (on) warm else muted))
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.defaultWeight()) {
                            Text(light.optString("name"), maxLines = 1, style = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                            Text(rooms[light.optString("room")] ?: "", maxLines = 1, style = TextStyle(color = muted, fontSize = 11.sp))
                        }
                        Text(if (!on) "Off" else if ("brightness" in light.optJSONArray("capabilities").toString())
                            "${(light.optDouble("brightness", 1.0) * 100).roundToInt()}%" else "On",
                            style = TextStyle(color = if (on) warm else muted, fontSize = 12.sp))
                    }
                    Spacer(GlanceModifier.height(5.dp))
                }
            }
        }
    }
}

class LightsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LightsWidget()
}
