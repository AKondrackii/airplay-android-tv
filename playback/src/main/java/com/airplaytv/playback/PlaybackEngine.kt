package com.airplaytv.playback

import android.content.Context
import android.view.Surface
import com.airplaytv.core.MediaSink
import com.airplaytv.core.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer

/**
 * Media playback engine: the [MediaSink] the AirPlay receiver feeds.
 * Lives in the background service, independent of any Activity. The player screen only
 * attaches/detaches its Surface.
 */
class PlaybackEngine(context: Context, scope: CoroutineScope) : MediaSink {
    val video = MirrorVideoDecoder()
    val audio = AudioEngine(context)
    val hls = HlsVideoPlayer(context)

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    /** Size of whatever video is showing (mirroring or AirPlay video), for letterboxing. */
    val videoSize: StateFlow<VideoSize?> = combine(video.videoSize, hls.videoSize, hls.active) { m, h, hlsOn ->
        if (hlsOn) h else m
    }.stateIn(scope, SharingStarted.Eagerly, null)

    @Volatile private var surface: Surface? = null
    @Volatile private var hlsActive = false

    fun configure(hardwareDecoding: Boolean, followSenderVolume: Boolean) {
        video.preferHardware = hardwareDecoding
        audio.followSenderVolume = followSenderVolume
    }

    /** Called by the player screen. Null when the screen goes away. */
    fun attachSurface(s: Surface?) {
        surface = s
        if (hlsActive) {
            hls.setSurface(s); video.setSurface(null)
        } else {
            video.setSurface(s); hls.setSurface(null)
        }
    }

    // ---- mirroring video ----
    override fun onVideoFormat(h265: Boolean, width: Int, height: Int) = video.onFormatHint(width, height)
    override fun onVideoData(data: ByteBuffer, length: Int, h265: Boolean) = video.decode(data, length, h265)
    override fun onVideoFlush() = video.flush()
    override fun onVideoStopped() = video.stop()

    // ---- audio ----
    override fun onAudioFormat(compressionType: Int, framesPerPacket: Int) {
        audio.onFormat(compressionType)
        _nowPlaying.value = NowPlaying()
    }
    override fun onAudioPcm(pcm: ByteBuffer, length: Int) = audio.writePcm(pcm, length)
    override fun onAudioCompressed(data: ByteBuffer, length: Int, compressionType: Int) =
        audio.decodeAac(data, length, compressionType)
    override fun onAudioFlush() = audio.flush()
    override fun onAudioVolume(volumeDb: Float) = audio.setVolumeDb(volumeDb)
    override fun onAudioStopped() {
        audio.stop()
        _nowPlaying.value = NowPlaying()
    }

    override fun onMetadata(dmap: ByteArray) {
        val info = DmapParser.parse(dmap)
        _nowPlaying.update { it.copy(title = info.title, artist = info.artist, album = info.album) }
    }

    override fun onCoverArt(jpeg: ByteArray) {
        // Kept in memory only for display; never written to storage.
        _nowPlaying.update { it.copy(coverArt = jpeg.takeIf { j -> j.size > 4 }) }
    }

    override fun onProgress(startRtp: Long, currentRtp: Long, endRtp: Long) {
        val total = (endRtp - startRtp) and 0xFFFFFFFFL
        val pos = (currentRtp - startRtp) and 0xFFFFFFFFL
        if (total == 0L || pos > total) return
        _nowPlaying.update {
            it.copy(positionSec = pos / AudioEngine.SAMPLE_RATE.toDouble(), durationSec = total / AudioEngine.SAMPLE_RATE.toDouble())
        }
    }

    // ---- AirPlay video (URL) ----
    override fun onHlsPlay(url: String, startPositionSec: Float) {
        hlsActive = true
        video.setSurface(null)
        hls.setSurface(surface)
        hls.play(url, startPositionSec)
    }
    override fun onHlsSeek(positionSec: Float) = hls.seek(positionSec)
    override fun onHlsRate(rate: Float) = hls.setRate(rate)
    override fun onHlsStop() {
        if (!hlsActive) return
        hlsActive = false
        hls.stop()
        video.setSurface(surface)
    }
    override fun hlsPlaybackInfo(): DoubleArray? = hls.playbackInfo()

    fun releaseAll() {
        video.release()
        audio.stop()
        hls.stop()
    }
}
