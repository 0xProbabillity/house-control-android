package dev.housecontrol.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.housecontrol.android.AppModel
import dev.housecontrol.android.R
import dev.housecontrol.android.sync.SyncPairing

/** Pairs this phone with House Control on a Mac: scan the code the Mac shows, or paste its link. */
@Composable
fun PairingScreen(model: AppModel, back: () -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }

    fun take(link: String?) {
        val pairing = link?.let(SyncPairing::parse)
        if (pairing == null) {
            problem = "That is not a House Control pairing code."
            model.web.haptic("error")
            return
        }
        problem = null
        model.pair(pairing)
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::take) }

    Screen("Pair with Mac or PC", onBack = back) {
        val name = model.macName
        if (name != null) {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.lucide_circle_check), null, Modifier.size(72.dp), tint = Palette.green)
                Text("Paired with $name", style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.Center))
                Text("Whenever both are on the same Wi-Fi and the app is open, a plan saved on one reaches the other a moment later.",
                    Modifier.padding(horizontal = 16.dp), style = TextStyle(fontSize = 15.sp, color = Palette.muted, textAlign = TextAlign.Center))
                PrimaryButton("Done", onClick = back)
            }
        } else {
            Card(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("On the computer", style = Type.title)
                    Numbered(1, "Click the house with the right mouse button — in the menu bar on a Mac, in the taskbar's tray on a PC — and choose **Pair Phone…**. A code appears.")
                    Numbered(2, "The first time, macOS or Windows asks whether House Control may use your local network: allow it.")
                    Numbered(3, "Scan the code with this phone.")
                }
            }
            PrimaryButton("Scan the code", icon = R.drawable.lucide_scan_qr_code) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Point at the code on the computer")
                    .setBeepEnabled(false).setOrientationLocked(false))
            }
            SecondaryButton("Paste pairing link", icon = R.drawable.lucide_clipboard_paste) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                take(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
            }
            problem?.let { Problem(it) }
            Footnote("Or press Copy Pairing Link on the computer, send it to this phone and paste it here. Nothing goes through a server: " +
                "the two talk over your Wi-Fi with a key only the code carries.")
        }
    }
}

@Composable
fun Numbered(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$number", Modifier.width(22.dp), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.accent))
        Spacer(Modifier.width(6.dp))
        Text(bold(text), style = TextStyle(fontSize = 15.sp, color = Palette.muted, lineHeight = 20.sp))
    }
}
