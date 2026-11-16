package com.airplaytv.app.ui

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import com.airplaytv.network.RemoteCommand
import kotlinx.coroutines.delay
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airplaytv.core.ContentType
import com.airplaytv.core.NowPlaying
import com.airplaytv.core.ReceiverStatus
import com.airplaytv.playback.PlaybackEngine

/**
 * Full-screen stage. The decoder lives in the service; this screen only lends it a
 * Surface while visible, so leaving the screen never interrupts the stream.
 */
@Composable
fun PlayerScreen(
    engine: PlaybackEngine,
    status: ReceiverStatus,
    fill: Boolean,
    onToggleFill: () -> Unit,
    remoteAvailable: Boolean = false,
    onRemote: (RemoteCommand) -> Unit = {},
) {
    val size by engine.videoSize.collectAsState()
    val now by engine.nowPlaying.collectAsState()
    val session = (status as? ReceiverStatus.Connected)?.session
    val showVideo = session?.content == ContentType.MIRRORING || session?.content == ContentType.VIDEO || size != null
    val focus = remember { FocusRequester() }
    var modeHint by remember { mutableStateOf<String?>(null) }
    var hintToken by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .focusRequester(focus)
            .onKeyEvent { e ->
                // Hardware media keys (many TV remotes have them) control the phone's player.
                val media = when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> RemoteCommand.PLAY_PAUSE
                    Key.MediaNext, Key.MediaFastForward -> RemoteCommand.NEXT
                    Key.MediaPrevious, Key.MediaRewind -> RemoteCommand.PREVIOUS
                    else -> null
                }
                if (media != null) {
                    if (e.type == KeyEventType.KeyUp && remoteAvailable) onRemote(media)
                    return@onKeyEvent true
                }
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!showVideo || !ok) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) {
                    onToggleFill()
                    modeHint = if (fill) "Вписать в экран" else "Заполнить экран"
                    hintToken++
                }
                true
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        // Letterbox ("fit") or crop ("fill") while always keeping the video's own aspect ratio.
        val videoRatio = size?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
        val screenRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val widthLimited = (videoRatio >= screenRatio) != fill
        val targetW = if (widthLimited) maxWidth else maxHeight * videoRatio
        val targetH = if (widthLimited) maxWidth / videoRatio else maxHeight
        val spec = tween<Dp>(durationMillis = 420, easing = FastOutSlowInEasing)
        val w by animateDpAsState(targetW, spec, label = "videoW")
        val h by animateDpAsState(targetH, spec, label = "videoH")

        val surfaceView = remember { mutableSurfaceHolder(engine) }
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { it.holder.addCallback(surfaceView) } },
            modifier = Modifier.requiredSize(w, h),
        )
        DisposableEffect(Unit) { onDispose { engine.attachSurface(null) } }

        if (!showVideo) {
            AuroraBackground(calm = true) {
                NowPlayingCard(now, session?.client?.displayName, engine, remoteAvailable, onRemote)
            }
        }
        session?.let {
            FadingChip("Подключено: ${it.client.displayName}  ·  OK — масштаб", Modifier.align(Alignment.TopStart).padding(28.dp))
        }
        modeHint?.let { hint ->
            key(hintToken) {
                FadingChip(hint, Modifier.align(Alignment.BottomCenter).padding(36.dp))
            }
        }
        if (session == null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(32.dp)) {
                StatusPill("Ожидание потока…", GlassTokens.Warning, pulse = true)
            }
        }
    }
    LaunchedEffect(showVideo) { if (showVideo) runCatching { focus.requestFocus() } }
}

private fun mutableSurfaceHolder(engine: PlaybackEngine) = object : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = engine.attachSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = engine.attachSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.attachSurface(null)
}

@Composable
private fun NowPlayingCard(
    now: NowPlaying,
    device: String?,
    engine: PlaybackEngine,
    remoteAvailable: Boolean,
    onRemote: (RemoteCommand) -> Unit,
) {
    // "Playing" = audio packets keep arriving; the phone pausing simply stops them.
    var tick by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var optimistic by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        while (true) { delay(400); tick = SystemClock.uptimeMillis() }
    }
    val streaming = tick - engine.lastAudioAt < 1_200
    LaunchedEffect(streaming) { optimistic = null }
    val playing = optimistic ?: streaming
    val position = if (now.durationSec > 0 && streaming && now.positionAt > 0)
        (now.positionSec + (tick - now.positionAt) / 1000.0).coerceAtMost(now.durationSec)
    else now.positionSec
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(remoteAvailable) { if (remoteAvailable) runCatching { playFocus.requestFocus() } }

    val art = remember(now.coverArt) {
        now.coverArt?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.width(720.dp), padding = 30.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(GlassTokens.RadiusM)).glass(RoundedCornerShape(GlassTokens.RadiusM)),
                    contentAlignment = Alignment.Center) {
                    if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else CastGlyph(Modifier.size(96.dp), GlassTokens.TextSecondary)
                }
                Column(Modifier.weight(1f)) {
                    GText(device?.let { "Звук с «$it»" } ?: "AirPlay-звук", GlassType.Caption)
                    Spacer(height = 8.dp)
                    val title = remember(now.title) { displayable(now.title) }
                    val artist = remember(now.artist) { displayable(now.artist) }
                    val album = remember(now.album) { displayable(now.album) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GText(title ?: "Воспроизведение", GlassType.Title, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        if (now.explicit) {
                            Spacer(width = 12.dp)
                            ExplicitBadge()
                        }
                    }
                    artist?.let { GText(it, GlassType.Headline, color = GlassTokens.TextSecondary, maxLines = 1) }
                    album?.let { GText(it, GlassType.Body, maxLines = 1) }
                    if (now.durationSec > 0) {
                        Spacer(height = 18.dp)
                        val f = (position / now.durationSec).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF))) {
                            Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(50)).background(GlassTokens.TextPrimary))
                        }
                        Spacer(height = 6.dp)
                        Row(Modifier.fillMaxWidth()) {
                            GText(fmt(position), GlassType.Caption)
                            Box(Modifier.weight(1f))
                            GText(fmt(now.durationSec), GlassType.Caption)
                        }
                    }
                    if (remoteAvailable) {
                        Spacer(height = 18.dp)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TransportButton(TransportIcon.PREVIOUS) { onRemote(RemoteCommand.PREVIOUS) }
                            TransportButton(if (playing) TransportIcon.PAUSE else TransportIcon.PLAY, Modifier.focusRequester(playFocus), big = true) {
                                optimistic = !playing
                                onRemote(RemoteCommand.PLAY_PAUSE)
                            }
                            TransportButton(TransportIcon.NEXT) { onRemote(RemoteCommand.NEXT) }
                        }
                    }
                }
            }
        }
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** "E" badge for explicit tracks, drawn by us (the 🅴 glyph is missing from most TV fonts). */
@Composable
private fun ExplicitBadge() {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(GlassTokens.TextSecondary),
        contentAlignment = Alignment.Center,
    ) { GText("E", GlassType.Label, color = GlassTokens.Ink) }
}

private val glyphPaint = Paint()

/**
 * Drops characters the TV's fonts cannot draw (Android would show a placeholder box or
 * a circled "!" instead). Text comes from the sender, so anything is possible.
 */
private fun displayable(text: String?): String? {
    if (text == null) return null
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val ch = String(Character.toChars(cp))
        if (Character.isWhitespace(cp) || glyphPaint.hasGlyph(ch)) sb.append(ch)
        i += Character.charCount(cp)
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim().ifEmpty { null }
}

private enum class TransportIcon { PREVIOUS, PLAY, PAUSE, NEXT }

/**
 * Transport button: just the vector icon (no glass shell). Focus is shown by a soft
 * light disc and a slight lift, so it stays visible from the sofa.
 */
@Composable
private fun TransportButton(icon: TransportIcon, modifier: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, label = "transportScale")
    val disc by animateColorAsState(if (focused) Color(0x2EFFFFFF) else Color.Transparent, label = "transportDisc")
    Box(
        modifier
            .scale(scale)
            .size(if (big) 64.dp else 52.dp)
            .clip(RoundedCornerShape(50))
            .background(disc)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(if (big) 26.dp else 20.dp)) {
            val w = size.width; val h = size.height
            val c = GlassTokens.TextPrimary
            fun tri(x0: Float, x1: Float) = Path().apply { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h); close() }
            when (icon) {
                TransportIcon.PLAY -> drawPath(tri(w * 0.12f, w), c)
                TransportIcon.PAUSE -> {
                    drawRoundRect(c, Offset(w * 0.12f, 0f), Size(w * 0.26f, h), CornerRadius(w * 0.06f))
                    drawRoundRect(c, Offset(w * 0.62f, 0f), Size(w * 0.26f, h), CornerRadius(w * 0.06f))
                }
                TransportIcon.NEXT -> {
                    drawPath(tri(0f, w * 0.78f), c)
                    drawRoundRect(c, Offset(w * 0.80f, 0f), Size(w * 0.18f, h), CornerRadius(w * 0.05f))
                }
                TransportIcon.PREVIOUS -> {
                    drawRoundRect(c, Offset(0f, 0f), Size(w * 0.18f, h), CornerRadius(w * 0.05f))
                    drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.22f, h / 2); lineTo(w, h); close() }, c)
                }
            }
        }
    }
}
