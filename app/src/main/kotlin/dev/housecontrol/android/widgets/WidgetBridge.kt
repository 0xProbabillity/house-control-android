package dev.housecontrol.android.widgets

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dev.housecontrol.android.HouseApp
import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.core.HomeSnapshot
import dev.housecontrol.android.core.PlanStore
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The home as the widgets, the tile and the shortcuts know it, kept in the app's private
 * storage: the panel's last snapshot, and what widgets did while the panel was not running.
 */
class WidgetStore(context: Context) {
    private val directory = File(context.filesDir, "widgets").apply { mkdirs() }
    private val snapshotFile = File(directory, "snapshot.json")
    private val queueFile = File(directory, "queue.json")

    fun snapshot(): HomeSnapshot? = runCatching { HomeSnapshot(JSONObject(snapshotFile.readText())) }.getOrNull()

    fun save(snapshot: HomeSnapshot) = PlanStore.writeAtomically(snapshotFile, snapshot.json.toString().toByteArray())

    /** An action a widget carried out itself (`delivered`) or left for the panel, for the home `home`. */
    data class Entry(val home: String, val action: HomeAction, val delivered: Boolean)

    fun queue(): List<Entry> = runCatching {
        JSONArray(queueFile.readText()).objects().map { Entry(it.getString("home"), HomeAction.from(it.getJSONObject("action")), it.optBoolean("delivered")) }
    }.getOrDefault(emptyList())

    fun enqueue(entry: Entry) {
        val all = JSONArray(queue().map { jsonOf("home" to it.home, "action" to it.action.json(), "delivered" to it.delivered) })
        all.put(jsonOf("home" to entry.home, "action" to entry.action.json(), "delivered" to entry.delivered))
        PlanStore.writeAtomically(queueFile, all.toString().toByteArray())
    }

    fun clearQueue() {
        queueFile.delete()
    }
}

/**
 * The app's side of the widgets: keeps the stored snapshot up to date with what the panel
 * shows, and hands the panel what widgets did while it was not looking.
 */
class WidgetBridge(private val context: Context, private val scope: CoroutineScope) {
    val store = WidgetStore(context)
    /**
     * Runs actions widgets queued (the panel sends them to the devices) and, when some were
     * already delivered by a widget itself, the state to show for them.
     */
    var onActions: ((run: JSONArray, adopt: JSONObject?) -> Unit)? = null
    /** Told about every snapshot the panel posts (the app shortcuts are built from it). */
    var onSnapshot: ((HomeSnapshot) -> Unit)? = null
    /** The home the panel shows, from its last snapshot. */
    private var identity: String? = null
    private var reload: Job? = null
    private var lastReload = 0L

    /** False until the panel page has started; actions wait for it. */
    var isReady = false
        set(value) {
            field = value
            if (value) takeActions() else identity = null
        }

    /** The panel's `widgets` message. */
    fun publish(message: JSONObject) {
        val snapshot = message.optJSONObject("snapshot")?.let(::HomeSnapshot) ?: return
        onSnapshot?.invoke(snapshot)
        val first = identity == null
        identity = snapshot.identity
        // The queue waited to learn which home this is. When it held something, this
        // snapshot is already out of date: the panel posts a new one once it has caught up.
        if (first && takeActions()) return
        store.save(snapshot)
        scheduleReload()
    }

    /** Takes the queue (when the app comes to the foreground, or the page starts). True when there was something for the panel. */
    fun takeActions(): Boolean {
        val home = identity ?: return false
        if (!isReady) return false
        val all = store.queue()
        if (all.isEmpty()) return false
        val snapshot = store.snapshot()
        store.clearQueue()
        // Made for another home than the one on screen: never carry those out here.
        val queued = all.filter { it.home == home }
        if (queued.isEmpty()) { scheduleReload(); return false }
        val run = JSONArray(queued.filter { !it.delivered }.map { it.action.json() })
        val adopt = if (queued.any { it.delivered }) snapshot?.panelState else null
        onActions?.invoke(run, adopt)
        return true
    }

    /** Widgets redraw at most a couple of times a second, however fast the panel changes. */
    fun scheduleReload() {
        reload?.cancel()
        val wait = maxOf(150L, 600L - (System.currentTimeMillis() - lastReload))
        reload = scope.launch {
            delay(wait)
            lastReload = System.currentTimeMillis()
            refreshAll(context)
        }
    }

    companion object {
        /** Redraws every widget and the Quick Settings tile from the stored snapshot. */
        suspend fun refreshAll(context: Context) {
            val manager = GlanceAppWidgetManager(context)
            for (widget in listOf(HomeWidget(), ScenesWidget(), LightsWidget())) {
                if (manager.getGlanceIds(widget.javaClass).isNotEmpty()) widget.updateAll(context)
            }
            runCatching { TileService.requestListeningState(context, ComponentName(context, AllLightsTile::class.java)) }
        }

        /**
         * Carries out what a widget, the tile or a shortcut asked for. With the panel on screen
         * it goes through the panel; otherwise the widget does it itself: the stored snapshot
         * changes at once, the linked devices get their commands, and the panel adopts the
         * new state when it next starts.
         */
        fun perform(context: Context, action: HomeAction) {
            val model = HouseApp.model(context)
            if (model.isForeground && model.pageReady) {
                model.web.call("widget", JSONArray().put(action.json()))
                return
            }
            val store = model.widgets.store
            val snapshot = store.snapshot() ?: return
            val (resolved, changes) = snapshot.apply(action)
            store.save(snapshot)
            if (changes.isNotEmpty()) {
                for (change in changes) model.hub.command(change.message) {}
                store.enqueue(WidgetStore.Entry(snapshot.identity, resolved, delivered = true))
            }
            model.scope.launch { refreshAll(context) }
        }
    }
}
