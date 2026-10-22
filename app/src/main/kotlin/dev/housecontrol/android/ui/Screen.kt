package dev.housecontrol.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.R

/**
 * A native screen: a bar with its title (and a way back or out), and content that scrolls,
 * on the panel's dark background, edge to edge with the system bars.
 */
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "Done",
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp)) {
            if (onBack != null) {
                Row(
                    Modifier.align(Alignment.CenterStart).clip(CircleShape).clickable(onClick = onBack).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.lucide_arrow_left), "Back", Modifier.size(22.dp), tint = Palette.text)
                }
            }
            Text(
                title,
                Modifier.align(Alignment.Center).padding(horizontal = 64.dp),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.text, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                trailing()
                if (onClose != null) {
                    Text(
                        closeLabel,
                        Modifier.clip(CircleShape).clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 10.dp),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.accent),
                    )
                }
            }
        }
        // On a tablet, or a phone on its side, the content keeps a readable width in the middle.
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                content()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** A row that opens something else: a screen of its own, or a page in the browser. */
@Composable
fun LinkRow(@DrawableRes icon: Int, title: String, detail: String, tint: Color = Palette.accent, external: Boolean = false,
            status: Color? = null, onClick: () -> Unit) {
    ListRow(icon, title, detail, tint, onClick, status) {
        if (external) Icon(painterResource(R.drawable.lucide_arrow_up_right), null, Modifier.size(17.dp), tint = Palette.faint)
        else Chevron()
    }
}
