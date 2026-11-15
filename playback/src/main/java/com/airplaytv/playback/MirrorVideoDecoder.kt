package com.airplaytv.playback

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.airplaytv.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

data class VideoSize(val width: Int, val height: Int)

/**
 * Low-latency decoder for the AirPlay screen-mirroring stream (H.264, or H.265 when
 * advertised). Frames arrive as Annex-B access units from the native receiver thread
 * and are rendered as soon as they are decoded (mirroring is a live stream).
 *
 * The decoder keeps running while no screen is visible: it then outputs to an
 * off-screen placeholder surface without rendering, and switches to the real surface
 * with setOutputSurface() when the player becomes visible — no new keyframe needed.
 */
class MirrorVideoDecoder {
    @Volatile var preferHardware = true

    private val lock = Any()
    private var codec: MediaCodec? = null
    private var codecH265 = false
    private var params: NalParser.ParameterSets? = null
    private var needKeyFrame = true
    private var drainThread: Thread? = null
    @Volatile private var running = false

    private var displaySurface: Surface? = null
    private var placeholderTexture: SurfaceTexture? = null
    private var placeholder: Surface? = null
    @Volatile private var rendering = false

    private var scratch = ByteArray(512 * 1024)
    private var hintedSize = VideoSize(1920, 1080)

    private val _size = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _size.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun onFormatHint(width: Int, height: Int) {
        if (width in 16..8192 && height in 16..8192) hintedSize = VideoSize(width, height)
    }

    /** Attach (or with null, detach) the on-screen surface. Called from the UI thread. */
    fun setSurface(surface: Surface?) = synchronized(lock) {
        displaySurface = surface?.takeIf { it.isValid }
        val c = codec ?: return@synchronized
        val target = displaySurface ?: placeholderSurface()
        try {
            c.setOutputSurface(target)
            rendering = displaySurface != null
        } catch (t: Throwable) {
            AppLog.w(TAG, "setOutputSurface failed, recreating decoder", t)
            releaseLocked()
        }
    }

    fun decode(buf: ByteBuffer, len: Int, h265: Boolean) {
        if (len <= 4 || len > MAX_FRAME) return
        if (scratch.size < len) scratch = ByteArray(len + len / 4)
        buf.position(0)
        buf.get(scratch, 0, len)

        synchronized(lock) {
            val nals = NalParser.split(scratch, len, h265)
            if (nals.isEmpty()) return
            val ps = NalParser.parameterSets(scratch, nals, h265)
            if (ps != null && (!ps.sameAs(params) || codec == null || codecH265 != h265)) {
                params = ps
                configureLocked(h265, ps)
                needKeyFrame = true
            }
            val c = codec ?: return
            if (needKeyFrame) {
                if (!NalParser.isKeyFrame(nals, h265)) return
                needKeyFrame = false
            }
            try {
                val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (index < 0) return   // decoder busy: drop this frame (live stream)
                val input = c.getInputBuffer(index) ?: return
                if (input.capacity() < len) {
                    c.queueInputBuffer(index, 0, 0, 0, 0)
                    needKeyFrame = true
                    return
                }
                input.clear()
                input.put(scratch, 0, len)
                c.queueInputBuffer(index, 0, len, System.nanoTime() / 1000, 0)
            } catch (t: Throwable) {
                AppLog.w(TAG, "decoder input failed; will reconfigure on next keyframe", t)
                releaseLocked()
            }
        }
    }

    fun flush() = synchronized(lock) {
        try { codec?.flush() } catch (_: Throwable) {}
        needKeyFrame = true
    }

    fun stop() = synchronized(lock) {
        releaseLocked()
        params = null
        _size.value = null
    }

    private fun configureLocked(h265: Boolean, ps: NalParser.ParameterSets) {
        releaseLocked()
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val name = selectDecoder(mime, preferHardware)
        val c = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        } catch (t: Throwable) {
            AppLog.e(TAG, "no decoder for $mime", t); return
        }
        val format = MediaFormat.createVideoFormat(mime, hintedSize.width, hintedSize.height).apply {
            if (h265) {
                setByteBuffer("csd-0", ByteBuffer.wrap((ps.vps ?: ByteArray(0)) + ps.sps + ps.pps))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(ps.sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(ps.pps))
            }
            setInteger(MediaFormat.KEY_MAX_WIDTH, maxOf(hintedSize.width, 1920))
            setInteger(MediaFormat.KEY_MAX_HEIGHT, maxOf(hintedSize.height, 1920))
            setInteger(MediaFormat.KEY_PRIORITY, 0)                       // real-time
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Vendor low-latency switches; unknown keys are ignored by other decoders.
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
        }
        val target = displaySurface ?: placeholderSurface()
        try {
            c.configure(format, target, null, 0)
            c.start()
        } catch (t: Throwable) {
            AppLog.e(TAG, "decoder ${c.name} failed to start", t)
            runCatching { c.release() }
            return
        }
        codec = c
        codecH265 = h265
        rendering = displaySurface != null
        running = true
        _active.value = true
        AppLog.i(TAG, "decoder ${c.name} started (${if (h265) "H.265" else "H.264"})")
        drainThread = Thread({ drainLoop(c) }, "mirror-video-out").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US) } catch (_: Throwable) { break }
            when {
                idx >= 0 -> runCatching { c.releaseOutputBuffer(idx, rendering) }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> runCatching {
                    val f = c.outputFormat
                    var w = f.getInteger(MediaFormat.KEY_WIDTH)
                    var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                    }
                    _size.value = VideoSize(w, h)
                }
            }
        }
    }

    private fun releaseLocked() {
        running = false
        drainThread?.let { t -> runCatching { t.join(200) } }
        drainThread = null
        codec?.let { c -> runCatching { c.stop() }; runCatching { c.release() } }
        codec = null
        _active.value = false
    }

    private fun placeholderSurface(): Surface {
        placeholder?.takeIf { it.isValid }?.let { return it }
        val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(16, 16) }
        placeholderTexture = tex
        return Surface(tex).also { placeholder = it }
    }

    fun release() {
        stop()
        synchronized(lock) {
            placeholder?.release(); placeholder = null
            placeholderTexture?.release(); placeholderTexture = null
        }
    }

    companion object {
        private const val TAG = "MirrorVideo"
        private const val INPUT_TIMEOUT_US = 30_000L
        private const val OUTPUT_TIMEOUT_US = 20_000L
        private const val MAX_FRAME = 8 * 1024 * 1024

        fun isSoftware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
            else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

        fun selectDecoder(mime: String, hardware: Boolean): String? {
            val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            val preferred = all.firstOrNull { isSoftware(it) != hardware }
            return (preferred ?: all.firstOrNull())?.name
        }

        /** True if a hardware HEVC decoder exists (then H.265 mirroring may be advertised). */
        fun hasHardwareHevc(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                !it.isEncoder && !isSoftware(it) &&
                    it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }
            }
        }.getOrDefault(false)

        /** Largest resolution the AVC decoder supports, used for the "auto" resolution mode. */
        fun maxAvcSize(): VideoSize = runCatching {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && !isSoftware(it) }
                .firstNotNullOfOrNull { info ->
                    info.supportedTypes.firstOrNull { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
                        ?.let { info.getCapabilitiesForType(it).videoCapabilities }
                }
            if (caps != null && caps.isSizeSupported(3840, 2160)) VideoSize(3840, 2160)
            else VideoSize(1920, 1080)
        }.getOrDefault(VideoSize(1920, 1080))
    }
}
