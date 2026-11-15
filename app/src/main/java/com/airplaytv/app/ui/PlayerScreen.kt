package com.airplaytv.app.ui

import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
fun PlayerScreen(engine: PlaybackEngine, status: ReceiverStatus) {
    val size by engine.videoSize.collectAsState()
    val now by engine.nowPlaying.collectAsState()
    val session = (status as? ReceiverStatus.Connected)?.session
    val showVideo = session?.content == ContentType.MIRRORING || session?.content == ContentType.VIDEO || size != null

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val ratio = size?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
        val surfaceView = remember { mutableSurfaceHolder(engine) }
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { it.holder.addCallback(surfaceView) } },
            modifier = Modifier.fillMaxSize().aspectRatio(ratio, matchHeightConstraintsFirst = ratio >= 16f / 9f),
        )
        DisposableEffect(Unit) { onDispose { engine.attachSurface(null) } }

        if (!showVideo) {
            AuroraBackground(calm = true) { NowPlayingCard(now, session?.client?.displayName) }
        }
        session?.let {
            FadingChip("Подключено: ${it.client.displayName}", Modifier.align(Alignment.TopStart).padding(28.dp))
        }
        if (session == null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(32.dp)) {
                StatusPill("Ожидание потока…", GlassTokens.Warning, pulse = true)
            }
        }
    }
}

private fun mutableSurfaceHolder(engine: PlaybackEngine) = object : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) = engine.attachSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = engine.attachSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.attachSurface(null)
}

@Composable
private fun NowPlayingCard(now: NowPlaying, device: String?) {
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
                    GText(now.title ?: "Воспроизведение", GlassType.Title, maxLines = 2)
                    now.artist?.let { GText(it, GlassType.Headline, color = GlassTokens.TextSecondary, maxLines = 1) }
                    now.album?.let { GText(it, GlassType.Body, maxLines = 1) }
                    if (now.durationSec > 0) {
                        Spacer(height = 18.dp)
                        val f = (now.positionSec / now.durationSec).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color(0x26FFFFFF))) {
                            Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(50)).background(GlassTokens.TextPrimary))
                        }
                        Spacer(height = 6.dp)
                        Row(Modifier.fillMaxWidth()) {
                            GText(fmt(now.positionSec), GlassType.Caption)
                            Box(Modifier.weight(1f))
                            GText(fmt(now.durationSec), GlassType.Caption)
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
