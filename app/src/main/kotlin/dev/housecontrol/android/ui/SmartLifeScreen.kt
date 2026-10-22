package dev.housecontrol.android.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.R
import dev.housecontrol.android.core.QrCode
import dev.housecontrol.android.core.readable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Signs this phone in to Smart Life. The login is confirmed by scanning a QR code with the
 * Smart Life app — which usually lives on this very phone. So the code goes to the gallery,
 * where Smart Life's scanner can open it, or onto another screen: a paired Mac shows it by itself.
 */
@Composable
fun SmartLifeScreen(model: AppModel, back: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var userCode by remember { mutableStateOf(model.suggestedUserCode) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<Pair<String, String>?>(null) } // token, QR content
    var onMac by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<Boolean?>(null) }

    fun begin() {
        busy = true
        problem = null
        scope.launch {
            try {
                val ticket = model.beginLogin(userCode)
                login = ticket
                onMac = model.showOnMac(ticket.second)
            } catch (error: Exception) {
                problem = error.readable
            }
            busy = false
        }
    }
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val content = login?.second
        saved = if (granted && content != null) saveToGallery(context, content) else false
        model.web.haptic(if (saved == true) "success" else "error")
    }
    fun save() {
        val content = login?.second ?: return
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saved = saveToGallery(context, content)
        model.web.haptic(if (saved == true) "success" else "error")
    }

    DisposableEffect(Unit) { onDispose { model.showOnMac(null) } }
    // Back from the Smart Life app: do not wait for the next poll to find out.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val (token, _) = login ?: return@LifecycleEventObserver
            if (event == Lifecycle.Event.ON_RESUME && !model.smartLifeConnected) scope.launch { model.finishLogin(token, userCode) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Ask every two seconds whether the login was confirmed, for as long as the code is up.
    LaunchedEffect(login) {
        val (token, _) = login ?: return@LaunchedEffect
        repeat(150) {
            delay(2000)
            if (model.finishLogin(token, userCode)) return@LaunchedEffect
        }
        login = null
        problem = "The code expired before it was scanned. Show a new one."
    }

    Screen("Smart Life", onBack = back) {
        val ticket = login
        when {
            model.smartLifeConnected -> Connected(model, done)
            ticket != null -> Column(verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val mac = model.macName
                if (onMac && mac != null) {
                    Card(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.Top) {
                            IconBadge(R.drawable.lucide_laptop, Palette.green)
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("The code is on $mac", style = Type.title)
                                Text("Switch to Smart Life on this phone, tap the scanner on its Home tab and point it at the Mac's screen. " +
                                    "The code stays there while you are away; come back here when you have confirmed.",
                                    style = TextStyle(fontSize = 14.sp, color = Palette.muted, lineHeight = 19.sp))
                            }
                        }
                    }
                } else {
                    Card(padding = 16.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("Scan it with Smart Life on this phone", style = Type.title)
                            Step(1, "Save the code to the gallery (below).")
                            Step(2, "In Smart Life tap **+** → **Scan**, then the **album** icon, and pick the code.")
                            Step(3, "Confirm the login and come back here.")
                            Text("Or show it on another screen: share it to a computer or tablet, or pair a Mac and it appears there.",
                                style = TextStyle(fontSize = 13.5.sp, color = Palette.faint, lineHeight = 18.sp))
                        }
                    }
                }
                val bitmap = remember(ticket.second) { QrCode.bitmap(ticket.second, 12).asImageBitmap() }
                Box(Modifier.size(250.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(18.dp)) {
                    Image(bitmap, "Smart Life sign-in code", Modifier.fillMaxWidth(), filterQuality = FilterQuality.None)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Palette.muted, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Waiting for you to confirm in Smart Life…", style = TextStyle(fontSize = 14.sp, color = Palette.muted))
                }
                if (!onMac) {
                    PrimaryButton(if (saved == true) "Saved to the gallery" else "Save code to the gallery",
                        icon = if (saved == true) R.drawable.lucide_circle_check else R.drawable.lucide_image_down) { save() }
                    if (saved == false) Problem("House Control could not add the picture. Share the code instead.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Share code", Modifier.weight(1f), R.drawable.lucide_share_2) { share(context, ticket.second) }
                    if (!onMac && model.macName != null) {
                        SecondaryButton("Show on Mac", Modifier.weight(1f), R.drawable.lucide_laptop) { onMac = model.showOnMac(ticket.second) }
                    }
                }
                TextLink("Cancel", color = Palette.muted) {
                    model.showOnMac(null)
                    login = null
                    saved = null
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(padding = 16.dp) {
                    InputField("User Code", userCode, { userCode = it }, "From the Smart Life app", monospace = true,
                        imeAction = ImeAction.Go, onSubmit = { if (userCode.isNotBlank()) begin() })
                    if (model.suggestedUserCode.isNotEmpty() && userCode == model.suggestedUserCode) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.lucide_laptop), null, Modifier.size(15.dp), tint = Palette.muted)
                            Spacer(Modifier.width(6.dp))
                            Text("Filled in from your Mac", style = TextStyle(fontSize = 13.sp, color = Palette.muted))
                        }
                    }
                }
                problem?.let { Problem(it) }
                PrimaryButton("Show sign-in code", icon = R.drawable.lucide_qr_code, enabled = userCode.isNotBlank(), busy = busy) { begin() }
                HelpCard("Where is the User Code?", "In the Smart Life (or Tuya Smart) app: **Me → Settings → Account and Security**, at the " +
                    "bottom of the list. No Tuya developer account is needed.")
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).background(Palette.warm.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Palette.warm))
        }
        Spacer(Modifier.width(12.dp))
        Text(bold(text), style = TextStyle(fontSize = 15.sp, color = Palette.text, lineHeight = 20.sp))
    }
}

@Composable
private fun Connected(model: AppModel, done: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(painterResource(R.drawable.lucide_shield_check), null, Modifier.size(72.dp), tint = Palette.green)
        Text("Smart Life is connected", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text))
        Text(
            when (model.linkedDevices) {
                0 -> "Now link the lamps and air conditioners of your plan to your devices: Settings → Devices, or in the editor."
                1 -> "1 device in your floor plan is now the real thing."
                else -> "${model.linkedDevices} devices in your floor plan are now the real thing."
            },
            Modifier.padding(horizontal = 20.dp), style = TextStyle(fontSize = 15.sp, color = Palette.muted, textAlign = TextAlign.Center),
        )
        PrimaryButton("Done", onClick = done)
    }
}

/** The code as a picture in Pictures/House Control, where Smart Life's scanner can pick it. */
private fun saveToGallery(context: Context, content: String): Boolean = runCatching {
    val png = QrCode.png(content, 16)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Smart Life sign-in code.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/House Control")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { it.write(png) } ?: return false
    true
}.getOrDefault(false)

private fun share(context: Context, content: String) {
    val file = File(context.cacheDir, "shared/Smart Life sign-in code.png").apply { parentFile?.mkdirs() }
    file.writeBytes(QrCode.png(content, 16))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Smart Life sign-in code"))
}
