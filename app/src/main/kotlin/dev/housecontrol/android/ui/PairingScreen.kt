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
import androidx.compose.ui.res.stringResource
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
            problem = context.getString(R.string.pairing_invalid_code)
            model.web.haptic("error")
            return
        }
        problem = null
        model.pair(pairing)
    }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::take) }

    Screen(stringResource(R.string.action_pair_mac), onBack = back) {
        val name = model.macName
        if (name != null) {
            Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.lucide_circle_check), null, Modifier.size(72.dp), tint = Palette.green)
                Text(stringResource(R.string.pairing_paired_with, name), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.text, textAlign = TextAlign.Center))
                Text(stringResource(R.string.pairing_paired_detail),
                    Modifier.padding(horizontal = 16.dp), style = TextStyle(fontSize = 15.sp, color = Palette.muted, textAlign = TextAlign.Center))
                PrimaryButton(stringResource(R.string.action_done), onClick = back)
            }
        } else {
            Card(padding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.pairing_on_computer), style = Type.title)
                    Numbered(1, stringResource(R.string.pairing_step_1))
                    Numbered(2, stringResource(R.string.pairing_step_2))
                    Numbered(3, stringResource(R.string.pairing_step_3))
                }
            }
            PrimaryButton(stringResource(R.string.action_scan_code), icon = R.drawable.lucide_scan_qr_code) {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(context.getString(R.string.pairing_scan_prompt))
                    .setBeepEnabled(false).setOrientationLocked(false))
            }
            SecondaryButton(stringResource(R.string.action_paste_link), icon = R.drawable.lucide_clipboard_paste) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                take(clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString())
            }
            problem?.let { Problem(it) }
            Footnote(stringResource(R.string.pairing_footnote))
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
