package dev.housecontrol.android.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.Banner
import dev.housecontrol.android.FileRequest
import dev.housecontrol.android.R
import dev.housecontrol.android.Route
import dev.housecontrol.android.core.readable
import dev.housecontrol.android.web.HomeWeb

/** The 3D home full screen, and the native screens that come up over it. */
@Composable
fun Root(model: AppModel) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val top = safe.getTop(density) / density.density
    val bottom = safe.getBottom(density) / density.density
    val left = safe.getLeft(density, direction) / density.density
    val right = safe.getRight(density, direction) / density.density
    LaunchedEffect(top, bottom, left, right, model.editorWeb) {
        model.web.setInsets(top, bottom, left, right)
        model.editorWeb?.setInsets(top, bottom, left, right)
    }
    FileRequests(model)

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        WebHost(model.web, Modifier.fillMaxSize())
        Overlay(model.showWelcome) { WelcomeScreen(model) }
        Overlay(model.showNewHome) {
            BackHandler { model.showNewHome = false }
            NewHomeScreen(model) { model.showNewHome = false }
        }
        Overlay(model.showSettings) { SettingsFlow(model) }
        Overlay(model.showEditor) { EditorScreen(model) }
        BannerView(model.banner, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 6.dp))
    }

    val choice = model.planChoice
    if (choice != null && !model.showEditor) {
        AlertDialog(
            onDismissRequest = { model.planChoice = null },
            containerColor = Palette.raised,
            title = { Text("Which home do you keep?", color = Palette.text) },
            text = {
                Text(
                    if (choice.macPlanName == choice.phonePlanName)
                        "“${choice.phonePlanName}” was changed both here and on ${choice.macName} since they last synced. The one you keep replaces the other on both."
                    else "This phone has “${choice.phonePlanName}”, ${choice.macName} has “${choice.macPlanName}”. The one you keep replaces the other on both.",
                    color = Palette.muted,
                )
            },
            confirmButton = { TextButton({ model.resolvePlanChoice(keepPhone = true) }) { Text("Keep this phone’s", color = Palette.accent) } },
            dismissButton = {
                Row {
                    TextButton({ model.planChoice = null }) { Text("Later", color = Palette.muted) }
                    TextButton({ model.resolvePlanChoice(keepPhone = false) }) { Text("Keep ${choice.macName}’s", color = Palette.accent) }
                }
            },
        )
    }
}

/** A web view in the layout. A renderer that died is replaced by a new view (its generation). */
@Composable
fun WebHost(web: HomeWeb, modifier: Modifier = Modifier) {
    key(web.generation) {
        AndroidView(
            factory = { context -> web.webView(context).also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = modifier,
        )
    }
}

/** A full-screen layer that slides up from the bottom, like a sheet. */
@Composable
private fun Overlay(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(260)) { it / 3 } + fadeOut(tween(200)),
    ) {
        Box(Modifier.fillMaxSize().background(Palette.background)) { content() }
    }
}

/** Settings and the screens under it, one after the other: back goes up a level. */
@Composable
private fun SettingsFlow(model: AppModel) {
    val stack = model.settingsStack
    val back = { if (stack.isEmpty()) model.showSettings = false else model.settingsStack = stack.dropLast(1) }
    BackHandler(onBack = back)
    AnimatedContent(
        stack,
        transitionSpec = {
            if (targetState.size >= initialState.size) slideInHorizontally(tween(280)) { it } togetherWith slideOutHorizontally(tween(280)) { -it / 4 }
            else slideInHorizontally(tween(280)) { -it / 4 } togetherWith slideOutHorizontally(tween(280)) { it }
        },
        label = "settings",
    ) { screens ->
        val open: (Route) -> Unit = { model.settingsStack = screens + it }
        when (val route = screens.lastOrNull()) {
            null -> SettingsScreen(model, open) { model.showSettings = false }
            else -> RouteScreen(model, route, insideEditor = false, open = open, back = back, done = { model.showSettings = false })
        }
    }
}

@Composable
private fun RouteScreen(model: AppModel, route: Route, insideEditor: Boolean, open: (Route) -> Unit, back: () -> Unit, done: () -> Unit) {
    when (route) {
        Route.Pairing -> PairingScreen(model, back)
        Route.SmartLife -> SmartLifeScreen(model, back, done)
        Route.Shortcuts -> ShortcutsScreen(model, back)
        Route.HomeAssistant -> HomeAssistantScreen(model, insideEditor, open, back)
        Route.Hue -> HueScreen(model, insideEditor, open, back)
        Route.Devices -> DevicesScreen(model, back)
    }
}

/** The editor full screen, and the connection screens it opens over itself. */
@Composable
private fun EditorScreen(model: AppModel) {
    val editor = model.editorWeb ?: return
    BackHandler { editor.back { handled -> if (!handled) model.editorClosed() } }
    Box(Modifier.fillMaxSize().imePadding()) { WebHost(editor, Modifier.fillMaxSize()) }
    val sheet = model.editorSheet
    AnimatedVisibility(sheet != null, enter = slideInVertically { it / 3 } + fadeIn(), exit = slideOutVertically { it / 3 } + fadeOut()) {
        if (sheet != null) {
            BackHandler { model.editorSheetClosed() }
            Box(Modifier.fillMaxSize().background(Palette.background)) {
                RouteScreen(model, sheet, insideEditor = true, open = {}, back = { model.editorSheetClosed() }, done = { model.editorSheetClosed() })
            }
        }
    }
}

/** The file chooser for the editor's Import and Export, and Settings → Import file. */
@Composable
private fun FileRequests(model: AppModel) {
    val context = LocalContext.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val data = uri?.let { runCatching { context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }.getOrNull() }
        try {
            model.fileImported(data)
        } catch (error: Exception) {
            model.show(Banner(Banner.Kind.Error, error.readable))
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val request = model.fileRequest as? FileRequest.Export
        val ok = uri != null && request != null &&
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(request.data) } }.isSuccess
        model.fileExported(ok)
    }
    LaunchedEffect(model.fileRequest) {
        when (val request = model.fileRequest) {
            FileRequest.Import -> import.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            is FileRequest.Export -> export.launch("${request.name}.json")
            null -> {}
        }
    }
}

@Composable
private fun BannerView(banner: Banner?, modifier: Modifier) {
    AnimatedVisibility(banner != null, modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val shown = banner ?: return@AnimatedVisibility
        val (icon, tint) = when (shown.kind) {
            Banner.Kind.Success -> R.drawable.lucide_circle_check to Palette.green
            Banner.Kind.Info -> R.drawable.lucide_info to Palette.accent
            Banner.Kind.Scene -> R.drawable.lucide_sparkles to Palette.warm
            Banner.Kind.Sync -> R.drawable.lucide_refresh_cw to Palette.accent
            Banner.Kind.Warning -> R.drawable.lucide_circle_alert to Palette.warm
            Banner.Kind.Error -> R.drawable.lucide_circle_alert to Palette.red
        }
        Row(
            Modifier.padding(horizontal = 20.dp).shadow(18.dp, CircleShape).clip(CircleShape).background(Palette.raised.copy(alpha = 0.96f))
                .border(1.dp, Palette.line, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), null, Modifier.size(17.dp), tint = tint)
            Spacer(Modifier.width(10.dp))
            Text(shown.text, style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.text), maxLines = 2)
        }
    }
}
