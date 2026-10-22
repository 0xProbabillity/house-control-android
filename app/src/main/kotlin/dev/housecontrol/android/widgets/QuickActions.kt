package dev.housecontrol.android.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.housecontrol.android.MainActivity
import dev.housecontrol.android.R
import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.core.HomeSnapshot

/**
 * The menu of the app's icon (press and hold it on the home screen): scenes, lamps and
 * "all lights off", picked in Settings → Shortcuts. Built from the panel's last snapshot.
 */
class QuickActions(private val context: Context) {
    /** One thing the menu can offer. `id` is "scene:cozy", "light:living.ceiling", "allLights:off". */
    data class Option(val id: String, val title: String, val icon: Int, val action: HomeAction)

    private val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
    var options by mutableStateOf(listOf<Option>()); private set
    /** The ids chosen, in order; null until the person picked some (then the first scenes are offered). */
    var chosen by mutableStateOf(prefs.getString("chosen", null)?.split(",")?.filter { it.isNotEmpty() }); private set
    private var lastPublished = ""

    fun update(snapshot: HomeSnapshot) {
        val next = mutableListOf<Option>()
        for (scene in snapshot.scenes) {
            next += Option("scene:${scene.optString("id")}", scene.optString("name"), sceneIcon(scene.optString("icon")),
                HomeAction(HomeAction.SCENE, scene.optString("id")))
        }
        if (snapshot.lights.isNotEmpty()) {
            next += Option("allLights:off", "All lights off", R.drawable.lucide_power, HomeAction(HomeAction.ALL_LIGHTS, on = false))
        }
        for (light in snapshot.lights) {
            next += Option("light:${light.optString("id")}", light.optString("name"), lampIcon(light.optString("type")),
                HomeAction(HomeAction.LIGHT, light.optString("id")))
        }
        options = next
        publish()
    }

    /** What the menu shows: the chosen ones that still exist, or the first scenes. */
    val active: List<Option>
        get() {
            val ids = chosen ?: return options.filter { it.id.startsWith("scene:") }.take(MAX)
            return ids.mapNotNull { id -> options.firstOrNull { it.id == id } }.take(MAX)
        }

    fun toggle(id: String) {
        val current = (chosen ?: active.map { it.id }).toMutableList()
        if (id in current) current.remove(id) else if (current.size < MAX) current += id else return
        chosen = current
        prefs.edit().putString("chosen", current.joinToString(",")).apply()
        publish()
    }

    private fun publish() {
        val list = active
        val signature = list.joinToString("|") { "${it.id}=${it.title}" }
        if (signature == lastPublished) return
        lastPublished = signature
        val shortcuts = list.mapIndexed { rank, option ->
            val uri = Uri.parse("housecontrol://action").buildUpon()
                .appendQueryParameter("kind", option.action.kind)
                .appendQueryParameter("title", option.title)
                .apply {
                    option.action.id?.let { appendQueryParameter("id", it) }
                    option.action.on?.let { appendQueryParameter("on", it.toString()) }
                }.build()
            ShortcutInfoCompat.Builder(context, option.id)
                .setShortLabel(option.title)
                .setIcon(icon(option.icon))
                .setRank(rank)
                .setIntent(Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java))
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
    }

    /** A Lucide glyph on the app's dark tile, as an adaptive icon. */
    private fun icon(res: Int): IconCompat {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(19, 24, 40))
        ContextCompat.getDrawable(context, res)?.mutate()?.let { glyph ->
            glyph.setTint(Color.rgb(255, 180, 107))
            val inset = (size * 0.34).toInt()
            glyph.setBounds(inset, inset, size - inset, size - inset)
            glyph.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    companion object {
        const val MAX = 4
    }
}
