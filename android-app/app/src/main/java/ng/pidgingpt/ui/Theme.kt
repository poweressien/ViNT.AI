package ng.pidgingpt.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ViNT palette. Sampled from docs/brand-assets/reference-play-icon-512.png (dominant pixel colours):
 *   background #0F1115 (brief said #0F1014), green #2FA36B (#2FA36F), surface #20242C (#20242D), border #373D4A (#383E49).
 * These four colours + white are the ONLY colours the UI may use. tools/audit_brand_palette.py enforces that.
 */
object Palette {
    val Background = Color(0xFF0F1115)
    val Green = Color(0xFF2FA36B)
    val Surface = Color(0xFF20242C)
    val Border = Color(0xFF373D4A)
    val White = Color(0xFFFFFFFF)

    // Text hierarchy = white at alpha (the border colour itself is only ~1.7:1 on the background, so it is never used for text).
    val TextPrimary = White
    val TextSecondary = White.copy(alpha = 0.66f)   // 8.5:1 on Background, 7.6:1 on Surface (measured, see docs/BRAND.md)
    val TextDisabled = White.copy(alpha = 0.38f)
    val OnGreen = Background                         // text on green: about 5.9:1 (white on green would be only about 3.2:1)
    val Scrim = Background.copy(alpha = 0.72f)
}

val PillShape = RoundedCornerShape(50)
val CardShape = RoundedCornerShape(22.dp)
val BubbleShape = RoundedCornerShape(22.dp)

private val Scheme = darkColorScheme(
    primary = Palette.Green, onPrimary = Palette.OnGreen, primaryContainer = Palette.Surface, onPrimaryContainer = Palette.White,
    inversePrimary = Palette.Green,
    secondary = Palette.Green, onSecondary = Palette.OnGreen, secondaryContainer = Palette.Surface, onSecondaryContainer = Palette.White,
    tertiary = Palette.Green, onTertiary = Palette.OnGreen, tertiaryContainer = Palette.Surface, onTertiaryContainer = Palette.White,
    background = Palette.Background, onBackground = Palette.White,
    surface = Palette.Surface, onSurface = Palette.White, surfaceVariant = Palette.Surface, onSurfaceVariant = Palette.TextSecondary,
    surfaceTint = Palette.Surface, inverseSurface = Palette.White, inverseOnSurface = Palette.Background,
    error = Palette.White, onError = Palette.Background, errorContainer = Palette.Surface, onErrorContainer = Palette.White,  // no red in ViNT: errors use icon + label + outline
    outline = Palette.Border, outlineVariant = Palette.Border, scrim = Palette.Background,
    surfaceBright = Palette.Surface, surfaceDim = Palette.Background, surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface, surfaceContainer = Palette.Surface, surfaceContainerHigh = Palette.Surface,
    surfaceContainerHighest = Palette.Surface,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ViNTTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides RippleConfiguration(color = Palette.White, rippleAlpha = RippleAlpha(0.16f, 0.16f, 0.10f, 0.20f)),
            LocalTextSelectionColors provides TextSelectionColors(handleColor = Palette.Green, backgroundColor = Palette.Green.copy(alpha = 0.40f)),
            LocalContentColor provides Palette.White,
            content = content,
        )
    }
}

// ---------- reusable, fully-specified components (no Material default colours) ----------

fun Modifier.vintCard(shape: androidx.compose.ui.graphics.Shape = CardShape): Modifier =
    this.background(Palette.Surface, shape).border(1.dp, Palette.Border, shape)

@Composable fun vintTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.White, unfocusedTextColor = Palette.White, disabledTextColor = Palette.TextDisabled, errorTextColor = Palette.White,
    focusedContainerColor = Palette.Surface, unfocusedContainerColor = Palette.Surface, disabledContainerColor = Palette.Surface, errorContainerColor = Palette.Surface,
    cursorColor = Palette.Green, errorCursorColor = Palette.Green,
    selectionColors = TextSelectionColors(Palette.Green, Palette.Green.copy(alpha = 0.40f)),
    focusedBorderColor = Palette.Green, unfocusedBorderColor = Palette.Border, disabledBorderColor = Palette.Border, errorBorderColor = Palette.White,
    focusedLeadingIconColor = Palette.Green, unfocusedLeadingIconColor = Palette.TextSecondary,
    focusedTrailingIconColor = Palette.Green, unfocusedTrailingIconColor = Palette.TextSecondary,
    focusedLabelColor = Palette.Green, unfocusedLabelColor = Palette.TextSecondary,
    focusedPlaceholderColor = Palette.TextSecondary, unfocusedPlaceholderColor = Palette.TextSecondary,
    focusedSupportingTextColor = Palette.TextSecondary, unfocusedSupportingTextColor = Palette.TextSecondary,
)

@Composable fun VintButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = Button(
    onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = PillShape,
    colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Palette.OnGreen,
        disabledContainerColor = Palette.Surface, disabledContentColor = Palette.TextDisabled),
    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) { Text(text) }

@Composable fun VintOutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = OutlinedButton(
    onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, shape = PillShape,
    border = BorderStroke(1.dp, Palette.Border),
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = Palette.White, disabledContentColor = Palette.TextDisabled),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) { Text(text) }

@Composable fun VintTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = TextButton(
    onClick = onClick, modifier = modifier, enabled = enabled, shape = PillShape,
    colors = ButtonDefaults.textButtonColors(contentColor = Palette.Green, disabledContentColor = Palette.TextDisabled)) { Text(text) }

/** Pill toggle that mirrors the reference icon: green pill with a dark knob when on. */
@Composable fun VintSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Palette.Background, checkedTrackColor = Palette.Green, checkedBorderColor = Palette.Green,
        uncheckedThumbColor = Palette.TextSecondary, uncheckedTrackColor = Palette.Surface, uncheckedBorderColor = Palette.Border,
        disabledCheckedThumbColor = Palette.Background, disabledCheckedTrackColor = Palette.Green.copy(alpha = 0.38f),
        disabledUncheckedThumbColor = Palette.TextDisabled, disabledUncheckedTrackColor = Palette.Surface, disabledUncheckedBorderColor = Palette.Border))

@Composable fun vintSliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Green, activeTrackColor = Palette.Green, inactiveTrackColor = Palette.Border,
    activeTickColor = Palette.Background, inactiveTickColor = Palette.TextSecondary, disabledThumbColor = Palette.TextDisabled,
    disabledActiveTrackColor = Palette.TextDisabled, disabledInactiveTrackColor = Palette.Border)

@Composable fun vintRadioColors() = RadioButtonDefaults.colors(selectedColor = Palette.Green, unselectedColor = Palette.TextSecondary,
    disabledSelectedColor = Palette.TextDisabled, disabledUnselectedColor = Palette.TextDisabled)

@Composable fun VintProgress(modifier: Modifier = Modifier) = LinearProgressIndicator(modifier = modifier, color = Palette.Green, trackColor = Palette.Border)

@Composable fun vintIconButtonColors() = IconButtonDefaults.iconButtonColors(contentColor = Palette.White, disabledContentColor = Palette.TextDisabled)

@Composable fun vintChipColors() = AssistChipDefaults.assistChipColors(containerColor = Palette.Surface, labelColor = Palette.White, leadingIconContentColor = Palette.Green)

@Composable fun VintDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, dismiss: String = "Cancel") = AlertDialog(
    onDismissRequest = onDismiss, shape = CardShape, containerColor = Palette.Surface, tonalElevation = 0.dp,
    iconContentColor = Palette.Green, titleContentColor = Palette.White, textContentColor = Palette.TextSecondary,
    title = { Text(title) }, text = { Text(text) },
    confirmButton = { VintTextButton(confirm, onConfirm) }, dismissButton = { TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Palette.White)) { Text(dismiss) } })

/** The ViNT mark drawn from the same measured geometry as the launcher icon (tools/make_brand_assets.py). */
@Composable fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 320f                // content spans about 307 x 317 reference units
        val ox = -102.6f * s + (this.size.width - 307.4f * s) / 2f
        val oy = -98f * s + (this.size.height - 317f * s) / 2f
        fun pill(x0: Float, y0: Float, x1: Float, y1: Float, fill: Color, stroke: Color? = null) {
            val r = (y1 - y0) / 2f * s
            drawRoundRect(fill, Offset(x0 * s + ox, y0 * s + oy), Size((x1 - x0) * s, (y1 - y0) * s), CornerRadius(r, r))
            if (stroke != null) drawRoundRect(stroke, Offset(x0 * s + ox, y0 * s + oy), Size((x1 - x0) * s, (y1 - y0) * s), CornerRadius(r, r), style = Stroke(3f * s))
        }
        pill(103f, 98f, 410f, 185f, Palette.Green)
        drawOval(Palette.Background, Offset((361.8f - 35.2f) * s + ox, (140.8f - 26f) * s + oy), Size(70.4f * s, 52f * s))
        pill(104.1f, 214.1f, 408.5f, 298.5f, Palette.Surface, Palette.Border)
        pill(104.1f, 329.1f, 292.5f, 413.5f, Palette.Surface, Palette.Border)
    }
}

@Composable fun StatusDot(active: Boolean) = Box(Modifier.size(8.dp).background(if (active) Palette.Green else Palette.Border, PillShape))
