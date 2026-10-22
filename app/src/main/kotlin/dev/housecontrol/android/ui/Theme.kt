package dev.housecontrol.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.housecontrol.android.R

/** The palette of the 3D panel, so the native screens read as the same app. */
object Palette {
    val background = Color(0xFF0A0E1A)
    val raised = Color(0xFF131828)
    val surface = Color.White.copy(alpha = 0.055f)
    val surfaceHigh = Color.White.copy(alpha = 0.1f)
    val line = Color.White.copy(alpha = 0.085f)
    val text = Color(0xFFF3F5FA)
    val muted = Color(0xFFDEE6F7).copy(alpha = 0.6f)
    val faint = Color(0xFFDEE6F7).copy(alpha = 0.36f)
    val accent = Color(0xFF3D8BFF)
    val warm = Color(0xFFFFB46B)
    val green = Color(0xFF4ADE80)
    val red = Color(0xFFFF786E)

    val smartLife = Color(0xFFFF7A3D)
    val homeAssistant = Color(0xFF18BCF2)
    val hue = Color(0xFFC38BFF)
}

@Composable
fun HouseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent, onPrimary = Color.White, background = Palette.background, surface = Palette.raised,
            onBackground = Palette.text, onSurface = Palette.text, error = Palette.red,
        ),
        content = content,
    )
}

object Type {
    val title = TextStyle(fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
    val detail = TextStyle(fontSize = 13.5.sp, color = Palette.muted)
    val body = TextStyle(fontSize = 15.sp, color = Palette.muted, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, color = Palette.faint, lineHeight = 18.sp)
}

/** A rounded glass panel: the container of every settings group. */
@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(22.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.fillMaxWidth().padding(horizontal = 6.dp).semantics { heading() },
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = Palette.faint),
    )
}

/** A group: its title, its cards and notes. */
@Composable
fun Section(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title != null) SectionTitle(title)
        content()
    }
}

@Composable
fun Footnote(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 6.dp), style = Type.footnote)
}

@Composable
fun Problem(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.lucide_circle_alert), null, Modifier.size(16.dp).padding(top = 2.dp), tint = Palette.red)
        Spacer(Modifier.width(8.dp))
        Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.red))
    }
}

/** The rounded square with an icon that starts a row. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color = Palette.accent, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        transition.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot").value
    } else 1f
    Box(Modifier.size(8.dp).alpha(alpha).shadow(4.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape).background(color))
}

@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(Palette.line))
}

/** A row of a card: icon, title and detail, and whatever goes at its end. */
@Composable
fun ListRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String? = null,
    tint: Color = Palette.accent,
    onClick: (() -> Unit)? = null,
    status: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 66.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.title, maxLines = 2)
            if (detail != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != null) {
                        StatusDot(status)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(detail, style = Type.detail, maxLines = 2)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun Chevron() = Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp), tint = Palette.faint)

@Composable
fun Check(selected: Boolean) = Icon(
    painterResource(if (selected) R.drawable.lucide_circle_check else R.drawable.lucide_circle), null,
    Modifier.size(24.dp), tint = if (selected) Palette.accent else Palette.faint,
)

@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return if (pressed) 0.97f else 1f
}

/** The blue call to action. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true,
                  busy: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.fillMaxWidth().heightIn(min = 54.dp).scale(pressScale(source)).alpha(if (enabled) 1f else 0.4f)
            .shadow(if (enabled) 12.dp else 0.dp, shape, spotColor = Palette.accent, ambientColor = Palette.accent)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF5AA2FF), Color(0xFF2F7BF2))))
            .clickable(source, null, enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else ButtonLabel(text, icon, Color.White, 17.sp)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, tint: Color = Palette.text,
                    enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.fillMaxWidth().heightIn(min = 50.dp).scale(pressScale(source)).alpha(if (enabled) 1f else 0.4f)
            .clip(shape).background(Palette.surfaceHigh).border(1.dp, Palette.line, shape)
            .clickable(source, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text, icon, tint, 16.sp)
    }
}

@Composable
private fun ButtonLabel(text: String, @DrawableRes icon: Int?, tint: Color, size: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(19.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold, color = tint), maxLines = 1)
    }
}

/** A small capsule button at the end of a row ("Pair", "Turn off"). */
@Composable
fun PillButton(text: String, tint: Color = Palette.accent, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.scale(if (pressScale(source) < 1f) 0.94f else 1f).defaultMinSize(minHeight = 32.dp).clip(CircleShape)
            .background(if (tint == Palette.accent) Palette.accent else Palette.surfaceHigh)
            .clickable(source, null, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (tint == Palette.accent) Color.White else tint))
    }
}

@Composable
fun TextLink(text: String, @DrawableRes icon: Int? = null, color: Color = Palette.accent, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(17.dp), tint = color)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color))
    }
}

/** A labelled text field in the style of the iPhone app. */
@Composable
fun InputField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    monospace: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted))
        val shape = RoundedCornerShape(14.dp)
        TextField(
            value, onValueChange,
            Modifier.fillMaxWidth().clip(shape).border(BorderStroke(1.dp, Palette.line), shape),
            singleLine = true,
            placeholder = { Text(placeholder, style = TextStyle(fontSize = 17.sp, color = Palette.faint)) },
            textStyle = TextStyle(fontSize = if (monospace) 19.sp else 17.sp, color = Palette.text,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboard, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Black.copy(alpha = 0.3f), unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                focusedIndicatorColor = Palette.accent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Palette.accent,
            ),
        )
    }
}

@Composable
fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = Palette.muted, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = TextStyle(fontSize = 15.sp, color = Palette.muted))
    }
}

/** The padding of a screen's scrolling content. */
val ScreenPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 32.dp)
