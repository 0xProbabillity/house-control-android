package dev.housecontrol.android

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.housecontrol.android.core.DeviceHub
import dev.housecontrol.android.core.HomeAction
import dev.housecontrol.android.core.HueService
import dev.housecontrol.android.core.KeystoreSecretStore
import dev.housecontrol.android.core.PlanEditor
import dev.housecontrol.android.core.PlanStore
import dev.housecontrol.android.core.QrCode
import dev.housecontrol.android.core.SecretStore
import dev.housecontrol.android.core.ServiceDiscovery
import dev.housecontrol.android.core.jsonOf
import dev.housecontrol.android.core.readable
import dev.housecontrol.android.core.string
import dev.housecontrol.android.sync.SyncClient
import dev.housecontrol.android.sync.SyncPairing
import dev.housecontrol.android.sync.SyncState
import dev.housecontrol.android.web.HomeWeb
import dev.housecontrol.android.widgets.QuickActions
import dev.housecontrol.android.widgets.WidgetBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A short message that slides in under the status bar. */
data class Banner(val kind: Kind, val text: String, val id: Long = System.nanoTime()) {
    enum class Kind { Success, Info, Scene, Sync, Warning, Error }
}

/** Where the settings open, and the connection screens the editor opens over itself. */
enum class Route {
    Pairing, SmartLife, Shortcuts, HomeAssistant, Hue, Devices;

    companion object {
        /** The screen a connection id of the web editor ("smartlife", "hue"…) stands for. */
        fun of(service: String) = when (service) {
            "smartlife" -> SmartLife
            "homeassistant" -> HomeAssistant
            "hue" -> Hue
            else -> null
        }
    }
}

/** Both this phone and the paired Mac changed the floor plan since they last agreed: the user picks which one stays. */
data class PlanChoice(val macName: String, val macPlanName: String, val phonePlanName: String, val macPlan: ByteArray)

/** A file the editor asked for: one to import, or one to save. */
sealed class FileRequest {
    data object Import : FileRequest()
    data class Export(val data: ByteArray, val name: String) : FileRequest()
}

/**
 * Everything the screens share: the floor plan, the devices, the link to the Mac. Lives as
 * long as the process (the widgets use it too); every method runs on the main thread.
 */
class AppModel(private val context: Context) {
    val scope: CoroutineScope = MainScope()
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val plans = PlanStore(File(context.filesDir, "home"))
    private val secrets = File(context.filesDir, "secrets")
    private fun secret(name: String): SecretStore = KeystoreSecretStore(File(secrets, "$name.enc"))
    val hub = DeviceHub(plans.directory, scope, ::secret)
    val sync = SyncClient(context, secret("sync"), scope)
    val discovery = ServiceDiscovery(context)
    val web = HomeWeb(context)
    val widgets = WidgetBridge(context, scope)
    val quickActions = QuickActions(context)

    private var pollJob: Job? = null
    private var bannerJob: Job? = null
    var pageReady = false
        private set
    private var active = false
    /** True while the app is on screen. */
    val isForeground get() = active
    /** A device a widget asked to show before the page was up. */
    private var pendingDevice: String? = null
    /** Quick actions picked before the page was up. */
    private val pendingQuick = mutableListOf<Pair<HomeAction, String>>()

    // Floor plan
    var activePlan by mutableStateOf(plans.activePlan); private set
    var hasCustomPlan by mutableStateOf(plans.hasCustomPlan); private set
    var customPlanName by mutableStateOf(plans.customPlanName); private set
    var linkedDevices by mutableIntStateOf(0); private set
    /** Goes up whenever the plan on disk changed, so screens reading `currentPlan` redraw. */
    var planVersion by mutableIntStateOf(0); private set

    // Mac sync
    var syncStatus by mutableStateOf(sync.status); private set
    var macName by mutableStateOf(sync.pairing?.name); private set
    var lastSync by mutableStateOf(prefs.getLong("lastSync", 0L).takeIf { it > 0 }); private set

    // Home Assistant and Hue (shared with the Mac), Smart Life (this phone's own)
    var homeAssistantName by mutableStateOf<String?>(null); private set
    var hueName by mutableStateOf<String?>(null); private set
    var smartLifeConnected by mutableStateOf(hub.cloud?.isSmartLifeConnected ?: false); private set
    var smartLifeAccount by mutableStateOf(hub.cloud?.account ?: ""); private set
    /** The User Code entered on the Mac, offered here so it need not be typed again. */
    var suggestedUserCode by mutableStateOf(""); private set

    // Editing the home on this phone
    /** The web editor, while it is open. */
    var editorWeb by mutableStateOf<HomeWeb?>(null); private set
    var showEditor by mutableStateOf(false)
    /** A connection screen opened from inside the editor. */
    var editorSheet by mutableStateOf<Route?>(null)
    /** The editor asked for a plan file to import, or to save one. */
    var fileRequest by mutableStateOf<FileRequest?>(null); private set
    private var editorPending: JSONObject? = null
    var planChoice by mutableStateOf<PlanChoice?>(null)

    // Presentation
    var showSettings by mutableStateOf(false)
    var settingsStack by mutableStateOf(listOf<Route>())
    var showWelcome by mutableStateOf(false)
    var showNewHome by mutableStateOf(false)
    var banner by mutableStateOf<Banner?>(null); private set

    init {
        hub.onStale = { poll() }
        reloadLinks()
        refreshConnections()
        // A phone paired before plans travelled both ways holds the Mac's plan as it was sent.
        if (sync.isPaired && !prefs.contains("syncedPlanRevision")) prefs.edit().putString("syncedPlanRevision", plans.customPlanRevision).apply()

        sync.deviceName = deviceName()
        sync.planRevision = { plans.customPlanRevision }
        sync.onStatus = { syncStatus = sync.status }
        sync.onState = { received(it) }
        prefs.getString("syncEndpoint", null)?.let(::useSyncEndpoint)
        suggestedUserCode = prefs.getString("userCode", "") ?: ""

        widgets.onActions = { run, adopt ->
            if (run.length() > 0) web.call("widget", run)
            if (adopt != null) web.call("adopt", adopt)
        }
        widgets.onSnapshot = { quickActions.update(it) }
        web.onMessage = ::handle
        web.load(::bootPayload)
        showWelcome = !prefs.getBoolean("welcomed", false)
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // MARK: - Life cycle

    /** The app came to the foreground (true) or left it (false). */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            sync.start()
            startPolling()
            widgets.takeActions()
        } else {
            sync.stop()
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = null
        if (!active || !pageReady || !hub.canPoll) return
        pollJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(12_000)
            }
        }
    }

    fun poll() {
        scope.launch { pollOnce() }
    }

    private suspend fun pollOnce() {
        val update = hub.poll() ?: return
        web.call("applyRemote", update)
    }

    // MARK: - Web UI

    private fun bootPayload(): JSONObject {
        val boot = plans.bootPayload()
        hub.cloud?.let { boot.put("smartLife", it.smartLifeInfo) }
        return boot
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                pageReady = true
                widgets.isReady = true
                pendingDevice?.let { web.call("openDevice", it) }
                pendingDevice = null
                val quick = pendingQuick.toList()
                pendingQuick.clear()
                for ((action, title) in quick) run(action, title)
                web.call("shown") // the camera sweep and the lights coming on, once per load
                startPolling()
            }
            "command" -> hub.command(message) { web.reply(message, it) }
            "widgets" -> widgets.publish(message)
            "openSettings" -> {
                settingsStack = emptyList()
                showSettings = true
            }
            "openEditor" -> openEditor()
        }
    }

    private fun reloadLinks() {
        hub.setLinks(if (plans.activePlan == "custom") plans.customPlan() else null)
        linkedDevices = hub.linkedDeviceCount
    }

    /** Re-reads everything that depends on the plan and shows it. */
    private fun reloadHome() {
        planVersion++
        activePlan = plans.activePlan
        hasCustomPlan = plans.hasCustomPlan
        customPlanName = plans.customPlanName
        reloadLinks()
        pageReady = false
        widgets.isReady = false
        web.reload()
    }

    // MARK: - Floor plans

    fun selectPlan(plan: String) {
        if (plan == activePlan) return
        plans.select(plan)
        reloadHome()
    }

    /** Takes a plan file picked in the file chooser as this phone's home. */
    fun importPlan(data: ByteArray) {
        val plan = PlanStore.validate(data)
        savePlan(plan)
        show(Banner(Banner.Kind.Success, "Floor plan imported"))
    }

    /** The plan the phone shows right now, as stored: the user's own, or a demo from the app. */
    val currentPlan: JSONObject?
        get() {
            if (plans.activePlan == "custom") plans.customPlan()?.let { return it }
            return bundledPlan(plans.activePlan)
        }

    fun bundledPlan(id: String): JSONObject? =
        runCatching { JSONObject(context.assets.open("web/plans/$id.json").bufferedReader().readText()) }.getOrNull()

    /** Stores an edited plan as this phone's home (a demo becomes the user's own), shows it and hands it to the Mac. */
    fun savePlan(plan: JSONObject) {
        val data = PlanStore.encode(plan)
        PlanStore.validate(data)
        plans.saveCustom(data)
        reloadHome()
        sync.sendPlan(data) // when the Mac is away, the next sync sends it
    }

    /** Starts a new home on this phone: an empty one with one room, or a copy of a demo layout. The editor opens on it. */
    fun createHome(name: String, template: String?) {
        val plan = template?.let(::bundledPlan) ?: PlanEditor.blank(name)
        plan.put("name", name)
        try {
            savePlan(plan)
        } catch (_: Exception) {
            show(Banner(Banner.Kind.Error, "Could not create the home"))
            return
        }
        showSettings = false
        showNewHome = false
        scope.launch {
            delay(350)
            openEditor()
        }
    }

    // MARK: - Editor

    fun openEditor() {
        if (editorWeb == null) {
            val editor = HomeWeb(context, mode = "editor")
            editor.onMessage = { handleEditor(it, editor) }
            editor.load(::editorBoot)
            editorWeb = editor
        }
        showSettings = false
        showEditor = true
    }

    /** Called once the editor's screen is gone. */
    fun editorClosed() {
        showEditor = false
        editorWeb?.destroy()
        editorWeb = null
        editorSheet = null
    }

    private fun editorBoot(): JSONObject {
        val boot = plans.bootPayload()
        if (!boot.has("config")) currentPlan?.let { boot.put("config", it) }
        hub.cloud?.let {
            boot.put("smartLife", it.smartLifeInfo)
            boot.put("tuya", it.tuyaInfo)
        }
        boot.put("services", hub.servicesInfo)
        return boot
    }

    private fun handleEditor(message: JSONObject, editor: HomeWeb) {
        when (message.optString("type")) {
            "close" -> editorClosed()
            "openConnection" -> editorSheet = Route.of(message.optString("service"))
            "savePlan" -> {
                val config = message.optJSONObject("config") ?: return
                try {
                    savePlan(config)
                    editor.haptic("success")
                    editor.reply(message, jsonOf("ok" to true))
                } catch (error: Exception) {
                    editor.reply(message, jsonOf("error" to error.readable))
                }
            }
            "importPlan" -> {
                editorPending = message
                fileRequest = FileRequest.Import
            }
            "exportPlan" -> {
                val config = message.optJSONObject("config") ?: return
                editorPending = message
                fileRequest = FileRequest.Export(PlanStore.encode(config), config.optString("name", "Home"))
            }
            else -> handleService(message) { editor.reply(message, it) }
        }
    }

    /** Settings → Import file: a plan file becomes this phone's home. */
    fun requestImport() {
        editorPending = null
        fileRequest = FileRequest.Import
    }

    /** The file picked for the editor's "Import…" (null: cancelled), or a plan picked in Settings. */
    fun fileImported(data: ByteArray?) {
        fileRequest = null
        val message = editorPending
        val editor = editorWeb
        editorPending = null
        if (message == null || editor == null) {
            if (data != null) importPlan(data)
            return
        }
        if (data == null) { editor.reply(message, jsonOf("cancelled" to true)); return }
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        if (json == null) editor.reply(message, jsonOf("error" to "This file is not a floor plan."))
        else editor.reply(message, jsonOf("config" to json))
    }

    fun fileExported(ok: Boolean) {
        fileRequest = null
        val message = editorPending ?: return
        editorPending = null
        editorWeb?.reply(message, if (ok) jsonOf("ok" to true) else jsonOf("cancelled" to true))
    }

    /** A connection screen opened from the editor was closed: the editor's lists follow. */
    fun editorSheetClosed() {
        editorSheet = null
        editorWeb?.call("servicesChanged", hub.servicesInfo)
    }

    /** What the web editor asks of the platforms; the same calls the Mac answers. */
    private fun handleService(message: JSONObject, reply: (JSONObject) -> Unit) {
        val cloud = hub.cloud
        when (message.optString("type")) {
            "services.info" -> reply(hub.servicesInfo)
            "devices.all" -> scope.launch {
                val (devices, errors) = hub.allDevices()
                reply(jsonOf("devices" to JSONArray(devices), "errors" to JSONObject(errors)))
            }
            "services.discover" -> scope.launch {
                val found = if (message.optString("service") == "hue") discovery.hueBridges() else discovery.homeAssistants()
                reply(jsonOf("found" to found))
            }
            "homeassistant.connect" -> scope.launch {
                try {
                    connectHomeAssistant(message.optString("url"), message.optString("token"))
                    reply(hub.servicesInfo)
                } catch (error: Exception) {
                    reply(jsonOf("error" to error.readable))
                }
            }
            "hue.pair" -> scope.launch {
                try {
                    if (pairHue(message.optString("host"), message.string("bridgeId"), message.string("name"))) reply(hub.servicesInfo)
                    else reply(jsonOf("waiting" to true))
                } catch (error: Exception) {
                    reply(jsonOf("error" to error.readable))
                }
            }
            "service.disconnect" -> {
                disconnect(message.optString("service"))
                reply(hub.servicesInfo)
            }
            "smartlife.begin" -> scope.launch {
                try {
                    val (token, content) = beginLogin(message.optString("userCode"))
                    reply(jsonOf("token" to token, "qr" to QrCode.dataUrl(content)))
                } catch (error: Exception) {
                    reply(jsonOf("error" to error.readable))
                }
            }
            "smartlife.poll" -> scope.launch {
                val done = finishLogin(message.optString("token"), message.optString("userCode"))
                reply(jsonOf("connected" to done, "info" to (cloud?.smartLifeInfo ?: JSONObject())))
            }
            "smartlife.signOut" -> {
                signOut()
                reply(cloud?.smartLifeInfo ?: JSONObject())
            }
            "tuya.devices" -> scope.launch {
                try {
                    reply(jsonOf("devices" to JSONArray(cloud?.listDevices().orEmpty())))
                } catch (error: Exception) {
                    reply(jsonOf("error" to error.readable))
                }
            }
            "tuya.save" -> try {
                cloud?.saveTuya(message.optString("accessId"), message.optString("accessSecret"), message.optString("region", "eu"))
                connectionsChanged()
                reply(cloud?.tuyaInfo ?: JSONObject())
            } catch (error: Exception) {
                reply(jsonOf("error" to error.readable))
            }
        }
    }

    // MARK: - Devices (linking without the editor)

    suspend fun allDevices() = hub.allDevices()

    /** Applies an edit to the current plan and saves it. */
    fun editPlan(change: (PlanEditor) -> Unit) {
        val plan = currentPlan ?: return
        val editor = PlanEditor(plan)
        change(editor)
        try {
            savePlan(editor.plan)
        } catch (_: Exception) {
            show(Banner(Banner.Kind.Error, "Could not save the floor plan"))
        }
    }

    // MARK: - Quick actions, widgets

    /** An app shortcut (long press on the icon) or a widget asking for the panel. */
    fun runQuickAction(action: HomeAction, title: String) {
        showSettings = false
        if (pageReady) run(action, title) else pendingQuick += action to title
    }

    /** Runs an action through the panel, which sends it to the devices. */
    private fun run(action: HomeAction, title: String) {
        web.call("widget", JSONArray().put(action.json()))
        web.haptic("medium")
        show(Banner(if (action.kind == HomeAction.SCENE) Banner.Kind.Scene else Banner.Kind.Success, title))
    }

    /** `housecontrol://open?device=…`, `…?screen=editor|settings`, `housecontrol://pair?…`. Returns true when handled. */
    fun open(link: String): Boolean {
        val uri = android.net.Uri.parse(link)
        if (uri.scheme == SyncPairing.SCHEME && uri.host == "open") {
            when (uri.getQueryParameter("screen")) {
                "editor" -> { if (!showWelcome && !showNewHome) openEditor(); return true }
                "settings" -> {
                    if (!showWelcome && !showNewHome && !showEditor) { settingsStack = emptyList(); showSettings = true }
                    return true
                }
            }
            showSettings = false
            uri.getQueryParameter("device")?.let { device ->
                if (pageReady) web.call("openDevice", device) else pendingDevice = device
            }
            return true
        }
        val pairing = SyncPairing.parse(link) ?: return false
        pair(pairing)
        return true
    }

    // MARK: - Mac sync

    fun pair(pairing: SyncPairing) {
        sync.pair(pairing)
        macName = pairing.name
        prefs.edit().putBoolean("welcomed", true).apply()
        showWelcome = false
        web.haptic("success")
        show(Banner(Banner.Kind.Sync, "Paired with ${pairing.name}"))
        if (active) sync.start()
    }

    fun unpair() {
        sync.unpair()
        syncedRevision = null
        planChoice = null
        macName = null
        lastSync = null
        suggestedUserCode = ""
        prefs.edit().remove("lastSync").remove("userCode").apply()
    }

    fun syncNow() = sync.refresh()

    /** Connects straight to `host:port` instead of searching for the Mac (an emulator cannot see Bonjour). */
    fun useSyncEndpoint(endpoint: String?) {
        val parts = endpoint?.split(":")
        sync.fixedEndpoint = if (parts?.size == 2) parts[0] to (parts[1].toIntOrNull() ?: 0) else null
        prefs.edit().putString("syncEndpoint", endpoint).apply()
    }

    /** The plan revision this phone and the Mac last agreed on: tells which side changed it. */
    private var syncedRevision: String?
        get() = prefs.getString("syncedPlanRevision", null)
        set(value) = prefs.edit().putString("syncedPlanRevision", value).apply()

    private fun received(state: SyncState) {
        macName = state.macName
        lastSync = System.currentTimeMillis()
        suggestedUserCode = state.userCode
        prefs.edit().putLong("lastSync", lastSync ?: 0).putString("userCode", state.userCode).apply()
        val shared = state.connections
        if (shared != null && hub.adoptConnections(shared)) {
            refreshConnections()
            reloadLinks()
            startPolling()
        }
        // Whatever this phone connected (or disconnected) since goes the other way.
        sync.sendConnections(hub.shareableConnections, smartLifeUserCode)
        reconcilePlan(state)
    }

    /**
     * Decides which way the floor plan goes: to whichever side did not change it since the
     * two last agreed. When both did, the user chooses.
     */
    private fun reconcilePlan(state: SyncState) {
        val local = plans.customPlanRevision
        val remote = state.revision
        if (local != null && local == remote) {
            syncedRevision = local
            planChoice = null
            return
        }
        if (local == null || !plans.customPlanFile.exists()) {
            // Nothing of our own: the Mac's plan, if it has one, is the home.
            if (remote.isNotEmpty()) adopt(state)
            return
        }
        val localData = plans.customPlanFile.readBytes()
        when {
            remote.isEmpty() || remote == syncedRevision -> sync.sendPlan(localData) // only this phone changed it
            local == syncedRevision -> adopt(state)
            state.plan != null -> {
                val theirs = runCatching { PlanStore.validate(state.plan).string("name") }.getOrNull() ?: "Home"
                planChoice = PlanChoice(state.macName, theirs, plans.customPlanName ?: "Home", state.plan)
            }
        }
    }

    /** Takes the Mac's plan as this phone's home. */
    private fun adopt(state: SyncState) {
        val data = state.plan ?: return
        if (runCatching { PlanStore.validate(data) }.isFailure) return
        // The first plan from the Mac becomes this phone's home; later ones only replace it
        // (someone who switched to a demo plan stays there).
        val first = !plans.hasCustomPlan
        try {
            plans.saveCustom(data, select = first || plans.activePlan == "custom")
        } catch (_: Exception) {
            show(Banner(Banner.Kind.Error, "Could not save the floor plan"))
            return
        }
        syncedRevision = PlanStore.revision(data)
        reloadHome()
        editorWeb?.call("planArrived", plans.customPlan() ?: JSONObject(), state.macName)
        web.haptic("success")
        show(Banner(Banner.Kind.Sync,
            if (first) "${plans.customPlanName ?: "Your home"} arrived from ${state.macName}" else "Updated from ${state.macName}"))
    }

    /** The answer to a [PlanChoice]. */
    fun resolvePlanChoice(keepPhone: Boolean) {
        val choice = planChoice ?: return
        planChoice = null
        if (keepPhone) {
            if (plans.customPlanFile.exists() && sync.sendPlan(plans.customPlanFile.readBytes())) {
                show(Banner(Banner.Kind.Sync, "Sent to ${choice.macName}"))
            } else {
                // Next time the Mac shows this plan unchanged, ours goes over.
                syncedRevision = PlanStore.revision(choice.macPlan)
                show(Banner(Banner.Kind.Warning, "${choice.macName} is not connected; it gets your plan next time"))
            }
        } else {
            adopt(SyncState(choice.macName, PlanStore.revision(choice.macPlan), choice.macPlan, suggestedUserCode, false))
        }
    }

    // MARK: - Smart Life

    /** The User Code of the Smart Life account, for the Mac (empty without Smart Life). */
    private val smartLifeUserCode get() = hub.cloud?.userCode ?: ""

    suspend fun beginLogin(userCode: String): Pair<String, String> =
        hub.cloud?.beginLogin(userCode) ?: throw IllegalStateException("Smart Life is not part of this build")

    /** True once the login was confirmed in the Smart Life app. */
    suspend fun finishLogin(token: String, userCode: String): Boolean {
        val cloud = hub.cloud ?: return false
        if (!cloud.finishLogin(token, userCode)) return false
        smartLifeConnected = cloud.isSmartLifeConnected
        smartLifeAccount = cloud.account
        sync.showQR(null)
        connectionsChanged()
        web.haptic("success")
        return true
    }

    /** Asks the paired Mac to display the login code; false when it is not connected. */
    fun showOnMac(content: String?) = sync.showQR(content)

    fun signOut() {
        hub.cloud?.signOut()
        smartLifeConnected = false
        smartLifeAccount = ""
        connectionsChanged()
    }

    // MARK: - Home Assistant, Hue

    suspend fun connectHomeAssistant(url: String, token: String) {
        hub.connectHomeAssistant(url, token)
        connectionsChanged()
        web.haptic("success")
    }

    /** True once paired; false while nobody has pressed the bridge's button yet. */
    suspend fun pairHue(host: String, bridgeId: String?, name: String?): Boolean {
        try {
            hub.pairHue(host, bridgeId, name)
        } catch (_: HueService.ButtonNotPressed) {
            return false
        }
        connectionsChanged()
        web.haptic("success")
        return true
    }

    fun disconnect(service: String) {
        hub.disconnect(service)
        connectionsChanged()
    }

    /** A platform was connected or disconnected here: read the devices again, tell the Mac. */
    private fun connectionsChanged() {
        refreshConnections()
        reloadLinks()
        startPolling()
        poll()
        sync.sendConnections(hub.shareableConnections, smartLifeUserCode)
        editorWeb?.call("servicesChanged", hub.servicesInfo)
    }

    val homeAssistantInfo: JSONObject get() = hub.homeAssistant.info
    val hueInfo: JSONObject get() = hub.hue.info

    /** True when at least one platform's devices can be linked. */
    val hasAnyConnection get() = smartLifeConnected || homeAssistantName != null || hueName != null

    private fun refreshConnections() {
        val ha = hub.homeAssistant.info
        homeAssistantName = if (ha.optBoolean("connected")) ha.optString("name", "Home Assistant") else null
        val hue = hub.hue.info
        hueName = if (hue.optBoolean("connected")) hue.optString("name", "Hue Bridge") else null
        smartLifeConnected = hub.cloud?.isSmartLifeConnected ?: false
        smartLifeAccount = hub.cloud?.account ?: ""
        linkedDevices = hub.linkedDeviceCount
    }

    // MARK: - Welcome, banners

    enum class WelcomeChoice { SetUpHere, Pair, Demo }

    fun finishWelcome(choice: WelcomeChoice) {
        prefs.edit().putBoolean("welcomed", true).apply()
        showWelcome = false
        if (choice == WelcomeChoice.Demo) return
        if (choice == WelcomeChoice.Pair) {
            settingsStack = listOf(Route.Pairing)
            showSettings = true
        } else {
            showNewHome = true
        }
    }

    fun show(next: Banner) {
        bannerJob?.cancel()
        banner = next
        bannerJob = scope.launch {
            delay(3200)
            banner = null
        }
    }
}
