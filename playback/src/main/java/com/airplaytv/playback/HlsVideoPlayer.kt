package com.airplaytv.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize as ExoVideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.airplaytv.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays "AirPlay video" (the sender hands over a URL instead of mirroring): either the
 * native HLS proxy on localhost (YouTube-style apps) or a direct http(s) media URL.
 *
 * ExoPlayer lives on the main thread; native callbacks are posted there. A snapshot of
 * the playback position is refreshed every 500 ms so the native layer can answer the
 * sender's /playback-info polls without blocking on the main thread.
 */
class HlsVideoPlayer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private val snapshot = AtomicReference<DoubleArray?>(null)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private val _size = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _size.asStateFlow()

    private val ticker = object : Runnable {
        override fun run() {
            updateSnapshot()
            if (player != null) main.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: ExoVideoSize) {
            if (videoSize.width > 0) _size.value = VideoSize(videoSize.width, videoSize.height)
        }
        override fun onPlayerError(error: PlaybackException) {
            AppLog.e(TAG, "video playback error: ${error.errorCodeName}")
        }
    }

    fun play(url: String, startSec: Float) { main.post {
        val p = player ?: ExoPlayer.Builder(context).build().also {
            it.addListener(listener)
            player = it
        }
        surface?.let { p.setVideoSurface(it) }
        p.setMediaItem(MediaItem.fromUri(url))
        if (startSec > 0f) p.seekTo((startSec * 1000).toLong())
        p.prepare()
        p.playWhenReady = true
        _active.value = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        AppLog.i(TAG, "AirPlay video started")
    } }

    fun seek(sec: Float) { main.post { player?.seekTo((sec * 1000).toLong().coerceAtLeast(0)) } }

    fun setRate(rate: Float) { main.post {
        val p = player ?: return@post
        when {
            rate == 0f -> p.pause()
            rate > 0f -> { p.setPlaybackSpeed(1f); p.play() }
        }
    } }

    fun stop() { main.post {
        main.removeCallbacks(ticker)
        player?.let { it.removeListener(listener); it.release() }
        player = null
        snapshot.set(null)
        _active.value = false
        _size.value = null
    } }

    fun setSurface(s: Surface?) { main.post {
        surface = s
        player?.let { if (s != null) it.setVideoSurface(s) else it.clearVideoSurface() }
    } }

    /** [duration, position, rate, readyToPlay, bufferEmpty, seekStart, seekDuration]. Any thread. */
    fun playbackInfo(): DoubleArray? = snapshot.get()

    private fun updateSnapshot() {
        val p = player ?: return snapshot.set(null)
        val dur = if (p.duration == C.TIME_UNSET) -1.0 else p.duration / 1000.0
        val pos = p.currentPosition / 1000.0
        val ready = p.playbackState == Player.STATE_READY
        val buffering = p.playbackState == Player.STATE_BUFFERING
        val rate = if (p.isPlaying) 1.0 else 0.0
        snapshot.set(doubleArrayOf(dur, pos, rate, if (ready) 1.0 else 0.0, if (buffering) 1.0 else 0.0,
            0.0, if (dur > 0) dur else 0.0))
    }

    private companion object { const val TAG = "AirPlayVideo" }
}
