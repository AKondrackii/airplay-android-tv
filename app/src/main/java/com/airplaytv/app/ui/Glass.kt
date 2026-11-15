package com.airplaytv.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Liquid Glass" design system for a 10-foot UI.
 *
 * Android cannot blur what is behind a composable on most TV boxes (RenderEffect needs
 * API 31 and is expensive), so glass is built from layered translucency instead:
 * a tinted fill, a vertical specular gradient, a hairline light rim brighter at the top,
 * and a soft drop glow — over an already-soft, slowly moving aurora background.
 * Layouts target a 960×540 dp canvas, which Android TV maps to 720p, 1080p and 4K alike.
 */
object GlassTokens {
    val Ink = Color(0xFF05070D)
    val InkRaised = Color(0xFF0B0F1A)
    val TextPrimary = Color(0xFFF5F7FB)
    val TextSecondary = Color(0xB3E6EBF5)
    val TextTertiary = Color(0x73E6EBF5)
    val Accent = Color(0xFF6FB7FF)
    val AccentWarm = Color(0xFFB28CFF)
    val Positive = Color(0xFF5EE3A1)
    val Warning = Color(0xFFFFC56B)
    val Negative = Color(0xFFFF7A85)

    val Fill = Color(0x14FFFFFF)
    val FillFocused = Color(0x2EFFFFFF)
    val RimTop = Color(0x66FFFFFF)
    val RimBottom = Color(0x0FFFFFFF)
    val RimFocused = Color(0xCCFFFFFF)

    val RadiusL = 28.dp
    val RadiusM = 20.dp
    val RadiusS = 14.dp
}

object GlassType {
    private val base = TextStyle(color = GlassTokens.TextPrimary, letterSpacing = 0.sp)
    val Display = base.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val Title = base.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    val Headline = base.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val Body = base.copy(fontSize = 16.sp, color = GlassTokens.TextSecondary, lineHeight = 22.sp)
    val Caption = base.copy(fontSize = 13.sp, color = GlassTokens.TextTertiary, letterSpacing = 0.4.sp)
    val Label = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val Mono = base.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
}

@Composable
fun GText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(TextStyle(color = color, textAlign = align ?: TextAlign.Unspecified)),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Draws the glass material inside [shape]. */
fun Modifier.glass(
    shape: Shape,
    focused: Boolean = false,
    tint: Color = Color.Transparent,
): Modifier = this
    .drawBehind {
        if (focused) {
            // soft outer glow
            drawRoundGlow(GlassTokens.Accent.copy(alpha = 0.22f), size)
        }
    }
    .clip(shape)
    .background(if (focused) GlassTokens.FillFocused else GlassTokens.Fill)
    .background(tint)
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (focused) 0.16f else 0.08f),
            0.45f to Color.White.copy(alpha = 0.02f),
            1f to Color.Transparent,
        ),
    )
    .border(
        width = if (focused) 1.5.dp else 1.dp,
        brush = Brush.verticalGradient(
            listOf(
                if (focused) GlassTokens.RimFocused else GlassTokens.RimTop,
                if (focused) GlassTokens.RimFocused.copy(alpha = 0.35f) else GlassTokens.RimBottom,
            ),
        ),
        shape = shape,
    )

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundGlow(color: Color, s: Size) {
    val r = maxOf(s.width, s.height) * 0.75f
    drawCircle(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(s.width / 2, s.height / 2), radius = r),
        radius = r,
        center = Offset(s.width / 2, s.height / 2),
    )
}

/**
 * Glass card. [solid] adds a dense dark base under the glass for modal dialogs, so text stays
 * readable over busy content (there is no real backdrop blur to separate the layers).
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = GlassTokens.RadiusL,
    padding: Dp = 24.dp,
    solid: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val base = if (solid) Modifier.clip(shape).background(GlassTokens.InkRaised.copy(alpha = 0.94f)) else Modifier
    Column(modifier.then(base).glass(shape).padding(padding), content = content)
}

/**
 * Focusable glass control. D-pad focus lifts it (scale + brighter rim + glow); OK/Enter
 * triggers [onClick]. Never relies on touch.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    radius: Dp = GlassTokens.RadiusM,
    tint: Color = Color.Transparent,
    contentPadding: Dp = 18.dp,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "scale")
    Row(
        modifier
            .scale(scale)
            .glass(RoundedCornerShape(radius), focused, tint)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = contentPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content(focused) }
}

@Composable
fun GlassLabelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Transparent) {
    GlassButton(onClick, modifier, tint = tint) { GText(text, GlassType.Label) }
}

/** Small capsule with a status dot, e.g. "● Сервис работает". */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val t = rememberInfiniteTransition(label = "pill")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Row(
        modifier.glass(RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = if (pulse) a else 1f)))
        GText(text, GlassType.Caption, color = GlassTokens.TextSecondary, maxLines = 1)
    }
}

/**
 * Slowly drifting colour field behind everything. Three large radial gradients are
 * cheap to draw; the animation is paused automatically when the composable leaves.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, calm: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    // The blobs move very slowly, so redrawing at ~20 fps is visually identical to 60 fps
    // and leaves the GPU of cheap TV boxes mostly idle.
    var p by remember { mutableFloatStateOf(0f) }
    val period = if (calm) 60_000L else 24_000L
    LaunchedEffect(period) {
        while (isActive) {
            p = (SystemClock.uptimeMillis() % period) / period.toFloat()
            delay(50)
        }
    }
    Box(modifier.fillMaxSize().background(GlassTokens.Ink)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val angle = p * 2 * Math.PI
            fun blob(cx: Float, cy: Float, r: Float, c: Color) = drawCircle(
                Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r),
                radius = r, center = Offset(cx, cy),
            )
            blob(w * (0.25f + 0.08f * kotlin.math.cos(angle).toFloat()), h * (0.30f + 0.06f * kotlin.math.sin(angle).toFloat()),
                w * 0.55f, Color(0x552B5BFF))
            blob(w * (0.78f + 0.06f * kotlin.math.sin(angle).toFloat()), h * (0.70f + 0.08f * kotlin.math.cos(angle).toFloat()),
                w * 0.50f, Color(0x447A3CFF))
            blob(w * (0.55f + 0.10f * kotlin.math.cos(angle + 2).toFloat()), h * (0.05f + 0.05f * kotlin.math.sin(angle).toFloat()),
                w * 0.35f, Color(0x3320C4E0))
            // vignette
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0xCC000000)), center = Offset(w / 2, h / 2), radius = w * 0.8f))
        }
        content()
    }
}

/** Original "screen with a triangle" casting glyph drawn with strokes (not Apple artwork). */
@Composable
fun CastGlyph(modifier: Modifier = Modifier, color: Color = GlassTokens.TextPrimary) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = w * 0.055f
        val frame = Path().apply {
            moveTo(w * 0.34f, h * 0.70f); lineTo(w * 0.16f, h * 0.70f)
            quadraticTo(w * 0.10f, h * 0.70f, w * 0.10f, h * 0.64f)
            lineTo(w * 0.10f, h * 0.22f)
            quadraticTo(w * 0.10f, h * 0.16f, w * 0.16f, h * 0.16f)
            lineTo(w * 0.84f, h * 0.16f)
            quadraticTo(w * 0.90f, h * 0.16f, w * 0.90f, h * 0.22f)
            lineTo(w * 0.90f, h * 0.64f)
            quadraticTo(w * 0.90f, h * 0.70f, w * 0.84f, h * 0.70f)
            lineTo(w * 0.66f, h * 0.70f)
        }
        drawPath(frame, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val tri = Path().apply {
            moveTo(w * 0.50f, h * 0.56f); lineTo(w * 0.72f, h * 0.86f); lineTo(w * 0.28f, h * 0.86f); close()
        }
        drawPath(tri, color)
    }
}

/** Glass orb containing the glyph, with a breathing halo; [active] tints it green. */
@Composable
fun ReceiverOrb(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val halo by animateColorAsState(if (active) GlassTokens.Positive else GlassTokens.Accent, tween(900), label = "halo")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().scale(breathe)) {
            drawCircle(Brush.radialGradient(listOf(halo.copy(alpha = 0.35f), Color.Transparent)), radius = size.minDimension / 2)
        }
        Box(
            Modifier.fillMaxSize(0.62f).glass(RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { CastGlyph(Modifier.fillMaxSize(0.56f)) }
    }
}

@Composable
fun Spacer(height: Dp = 0.dp, width: Dp = 0.dp) =
    androidx.compose.foundation.layout.Spacer(Modifier.size(width = width, height = height))
