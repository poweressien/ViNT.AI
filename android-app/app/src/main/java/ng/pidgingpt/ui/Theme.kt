package ng.pidgingpt.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

enum class BotMood { Idle, Thinking, Listening, Speaking }

/** The ViNT robot head (the dot of the "i"). Drawn in the 4-colour palette. Static when [mood] is Idle (no animation cost); otherwise it animates. */
@Composable fun BotHead(size: Dp, modifier: Modifier = Modifier, mood: BotMood = BotMood.Idle) {
    if (mood == BotMood.Idle) BotCanvas(size, modifier, glow = 0f, mouth = 0f, blink = 0f, bob = 0f)
    else AnimatedBot(size, modifier, mood)
}

@Composable private fun AnimatedBot(size: Dp, modifier: Modifier, mood: BotMood) {
    val t = rememberInfiniteTransition(label = "bot")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val talk by t.animateFloat(0f, 1f, infiniteRepeatable(tween(220, easing = LinearEasing), RepeatMode.Reverse), label = "talk")
    val cycle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "cycle")
    val blink = if (cycle > 0.93f && cycle < 0.98f) 1f else 0f
    BotCanvas(size, modifier, glow = pulse, mouth = if (mood == BotMood.Speaking) talk else 0f, blink = blink,
        bob = if (mood == BotMood.Thinking) pulse * 2f - 1f else 0f)
}

@Composable private fun BotCanvas(size: Dp, modifier: Modifier, glow: Float, mouth: Float, blink: Float, bob: Float) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 120f
        val c = Offset(60f * u, 60f * u + bob * 3f * u)
        if (glow > 0f) drawCircle(color = Palette.Green.copy(alpha = 0.10f + 0.22f * glow), radius = (44f + 8f * glow) * u, center = c)
        for (sx in listOf(-1f, 1f)) {
            val ex = c.x + (sx * 45f - 5.5f) * u
            drawRoundRect(Palette.Background, Offset(ex, c.y - 17f * u), Size(11f * u, 34f * u), CornerRadius(5.5f * u, 5.5f * u))
            drawRoundRect(Palette.Green, Offset(ex, c.y - 17f * u), Size(11f * u, 34f * u), CornerRadius(5.5f * u, 5.5f * u), style = Stroke(3.2f * u))
        }
        drawCircle(color = Palette.Background, radius = 40f * u, center = c)
        drawCircle(color = Palette.Green, radius = 40f * u, center = c, style = Stroke(5.5f * u))
        for (sx in listOf(-1f, 1f)) {
            val ex = c.x + sx * 14f * u; val ey = c.y - 7f * u
            val h = (9.5f * (1f - blink)).coerceAtLeast(0.8f)
            drawOval(Palette.White, Offset(ex - 9.5f * u, ey - h * u), Size(19f * u, 2f * h * u))
            if (blink < 0.5f) drawCircle(color = Palette.Green, radius = 4.6f * u, center = Offset(ex, ey + 2.5f * u))
        }
        val nose = Path().apply { moveTo(c.x - 4.5f * u, c.y + 8f * u); lineTo(c.x + 4.5f * u, c.y + 8f * u); lineTo(c.x, c.y + 16f * u); close() }
        drawPath(nose, Palette.Green)
        if (mouth > 0.05f) drawOval(Palette.White, Offset(c.x - 10f * u, c.y + 19f * u), Size(20f * u, (3f + 9f * mouth) * u))
        else drawArc(Palette.White, 20f, 140f, false, Offset(c.x - 17f * u, c.y + 8f * u), Size(34f * u, 24f * u), style = Stroke(3.6f * u, cap = StrokeCap.Round))
    }
}

/** The ViNT mark: speech bubble with a V and the robot head (same layout as the launcher icon). [active] makes the head animate (thinking). */
@Composable fun BrandMark(size: Dp, modifier: Modifier = Modifier, active: Boolean = false) {
    Box(modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width; val h = this.size.height
            drawRoundRect(Palette.White, Offset(0.04f * w, 0.28f * h), Size(0.64f * w, 0.62f * h), CornerRadius(0.14f * w, 0.14f * w))
            val tail = Path().apply { moveTo(0.12f * w, 0.82f * h); lineTo(0.03f * w, 0.98f * h); lineTo(0.30f * w, 0.86f * h); close() }
            drawPath(tail, Palette.White)
            val v = Path().apply { moveTo(0.18f * w, 0.46f * h); lineTo(0.36f * w, 0.76f * h); lineTo(0.54f * w, 0.46f * h) }
            drawPath(v, Palette.Background, style = Stroke(0.10f * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        BotHead(size * 0.62f, Modifier.align(Alignment.TopEnd), if (active) BotMood.Thinking else BotMood.Idle)
    }
}

/** Shown in place of an empty reply bubble while ViNT loads the model or generates: the robot head animates. */
@Composable fun ThinkingBubble(loadingModel: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BotHead(36.dp, mood = BotMood.Thinking); Spacer(Modifier.width(10.dp))
        Column {
            Text(if (loadingModel) "Loading the model\u2026" else "Thinking\u2026", fontSize = 14.sp, color = Palette.White)
            if (loadingModel) Text("The first reply can take a minute.", fontSize = 11.sp, color = Palette.TextSecondary)
        }
    }
}

@Composable fun StatusDot(active: Boolean) = Box(Modifier.size(8.dp).background(if (active) Palette.Green else Palette.Border, PillShape))
