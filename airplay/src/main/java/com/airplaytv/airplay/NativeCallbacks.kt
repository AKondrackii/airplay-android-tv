package com.airplaytv.airplay

import com.airplaytv.core.AppLog
import com.airplaytv.core.ClientInfo
import com.airplaytv.core.ConnectionPolicy
import com.airplaytv.core.MediaSink
import java.nio.ByteBuffer

/**
 * Receives every callback from the native receiver. Method names and signatures are
 * looked up by JNI (airplay_jni.c: lookup_methods) — keep them in sync.
 *
 * All methods run on native threads. Exceptions are caught here so a bug in the
 * playback layer can never unwind into C code.
 */
internal class NativeCallbacks(
    private val policy: ConnectionPolicy,
    private val sink: MediaSink,
    private val events: ReceiverEvents,
) {
    interface ReceiverEvents {
        fun onConnectionCountChanged(delta: Int)
        fun onConnectionLost(reason: Int)
        fun onClientAdmitted(client: ClientInfo)
        fun onMirroring(running: Boolean)
        fun onAudioStream(compressionType: Int, isMedia: Boolean, usingScreen: Boolean)
        fun onHlsStarted()
        fun onHlsStopped()
        fun onStreamsReset(type: Int)
    }

    private inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { AppLog.e(TAG, "callback $name failed", t) }
    }

    private inline fun <T> guard(name: String, fallback: T, block: () -> T): T =
        try { block() } catch (t: Throwable) { AppLog.e(TAG, "callback $name failed", t); fallback }

    // ---- logging ---------------------------------------------------------
    fun onLog(level: Int, msg: String?) {
        if (msg == null) return
        // UxPlay levels: 0 EMERG ... 3 ERR, 4 WARNING, 5 NOTICE, 6 INFO, 7 DEBUG
        val lvl = when {
            level <= 3 -> AppLog.Level.ERROR
            level == 4 -> AppLog.Level.WARN
            level <= 6 -> AppLog.Level.INFO
            else -> AppLog.Level.DEBUG
        }
        AppLog.log(lvl, "UxPlay", msg.trimEnd())
    }

    // ---- security ----------------------------------------------------------
    fun acceptConnection(local: ByteArray, remote: ByteArray): Boolean =
        guard("acceptConnection", false) { policy.acceptConnection(local, remote) }

    fun onClientRequest(deviceId: String?, model: String?, name: String?): Boolean =
        guard("onClientRequest", false) {
            val client = ClientInfo(deviceId, model, name)
            val ok = policy.admitClient(client)
            if (ok) events.onClientAdmitted(client)
            ok
        }

    fun onDisplayPin(pin: String?) = guard("onDisplayPin") { if (pin != null) policy.onPinDisplayed(pin) }

    fun onRegisterClient(deviceId: String?, pk: String?, name: String?) = guard("onRegisterClient") {
        if (deviceId != null && pk != null) policy.onClientPaired(deviceId, pk, name ?: deviceId)
    }

    fun checkRegister(pk: String?): Boolean =
        guard("checkRegister", false) { pk != null && policy.isPairedKeyTrusted(pk) }

    // ---- connection lifecycle ---------------------------------------------
    fun onConnectionOpened() = guard("opened") { events.onConnectionCountChanged(+1) }
    fun onConnectionClosed() = guard("closed") { events.onConnectionCountChanged(-1) }
    fun onConnectionReset(reason: Int) = guard("reset") { events.onConnectionLost(reason) }

    // ---- video (mirroring) -------------------------------------------------
    fun onVideoCodec(h265: Boolean, srcW: Int, srcH: Int, w: Int, h: Int) =
        guard("onVideoCodec") { sink.onVideoFormat(h265, if (w > 0) w else srcW, if (h > 0) h else srcH) }

    fun onVideoData(buf: ByteBuffer, len: Int, ntpRemote: Long, h265: Boolean) =
        guard("onVideoData") { sink.onVideoData(buf, len, h265) }

    fun onVideoFlush() = guard("onVideoFlush") { sink.onVideoFlush() }
    fun onVideoPause() = Unit
    fun onVideoResume() = Unit

    fun onVideoReset(type: Int) = guard("onVideoReset") {
        sink.onVideoStopped()
        events.onStreamsReset(type)
    }

    fun onMirrorRunning(running: Boolean) = guard("onMirrorRunning") {
        if (!running) sink.onVideoStopped()
        events.onMirroring(running)
    }

    // ---- audio -----------------------------------------------------------
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean) = guard("onAudioFormat") {
        sink.onAudioFormat(ct, spf)
        events.onAudioStream(ct, isMedia, usingScreen)
    }

    fun onAudioPcm(buf: ByteBuffer, len: Int) = guard("onAudioPcm") { sink.onAudioPcm(buf, len) }
    fun onAudioCompressed(buf: ByteBuffer, len: Int, ct: Int) =
        guard("onAudioCompressed") { sink.onAudioCompressed(buf, len, ct) }
    fun onAudioFlush() = guard("onAudioFlush") { sink.onAudioFlush() }
    fun onAudioVolume(db: Float) = guard("onAudioVolume") { sink.onAudioVolume(db) }
    fun onMetadata(data: ByteArray) = guard("onMetadata") { sink.onMetadata(data) }
    fun onCoverArt(data: ByteArray) = guard("onCoverArt") { sink.onCoverArt(data) }
    fun onProgress(start: Long, curr: Long, end: Long) = guard("onProgress") { sink.onProgress(start, curr, end) }

    // ---- AirPlay video (HLS) ------------------------------------------------
    fun onVideoPlay(url: String?, start: Float) = guard("onVideoPlay") {
        if (url == null) return@guard
        // Either the native HLS proxy (http://localhost:<port>/master.m3u8) or a direct
        // http(s) media URL from the sender (e.g. Safari). Never file://, content:// etc.
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") {
            AppLog.w(TAG, "ignoring video location with unsupported scheme")
            return@guard
        }
        events.onHlsStarted()
        sink.onHlsPlay(url, start)
    }

    fun onVideoScrub(pos: Float) = guard("onVideoScrub") { sink.onHlsSeek(pos) }
    fun onVideoRate(rate: Float) = guard("onVideoRate") { sink.onHlsRate(rate) }
    fun onVideoStop() = guard("onVideoStop") {
        sink.onHlsStop()
        events.onHlsStopped()
    }

    fun getPlaybackInfo(): DoubleArray? = guard("getPlaybackInfo", null) { sink.hlsPlaybackInfo() }

    fun onPlaylistRemove(): Float = guard("onPlaylistRemove", -1f) {
        val info = sink.hlsPlaybackInfo()
        sink.onHlsStop()
        events.onHlsStopped()
        info?.getOrNull(1)?.toFloat() ?: -1f
    }

    private companion object { const val TAG = "AirPlayCallbacks" }
}
