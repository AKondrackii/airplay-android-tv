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
 * iOS sends a keyframe essentially only when mirroring starts, so this class must never
 * end up "waiting for the next keyframe":
 *  - the last keyframe access unit is cached and re-fed whenever the decoder is
 *    (re)created (surface change, configure retry, error recovery);
 *  - flush requests do not reset decoder state (UxPlay issues one whenever *any* RTSP
 *    connection closes, e.g. another device probing /info);
 *  - configuration falls back to progressively safer formats and finally to a
 *    software decoder if the hardware decoder refuses the stream.
 *
 * While no screen is visible the decoder outputs to an off-screen placeholder surface
 * without rendering and switches to the real one with setOutputSurface().
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

    /** Last keyframe access unit (Annex-B, including SPS/PPS when it carried them). */
    private var keyFrame: ByteArray? = null
    private var keyFrameH265 = false

    private var displaySurface: Surface? = null
    private var placeholderTexture: SurfaceTexture? = null
    private var placeholder: Surface? = null
    @Volatile private var rendering = false

    private var scratch = ByteArray(512 * 1024)
    private var hintedSize = VideoSize(1920, 1080)
    @Volatile private var framesRendered = 0L

    private val _size = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _size.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun onFormatHint(width: Int, height: Int) {
        if (width in 16..8192 && height in 16..8192) hintedSize = VideoSize(width, height)
    }

    /** Attach (or with null, detach) the on-screen surface. Called from the UI thread. */
    fun setSurface(surface: Surface?) = synchronized(lock) {
        val newSurface = surface?.takeIf { it.isValid }
        if (newSurface === displaySurface && codec != null) return@synchronized
        displaySurface = newSurface
        val c = codec
        if (c == null) {
            // A stream is known but no decoder runs (e.g. it failed on the placeholder):
            // start it now on the real surface from the cached keyframe.
            params?.let { ps -> if (newSurface != null) configureLocked(codecH265 || keyFrameH265, ps, refeed = true) }
            return@synchronized
        }
        val target = displaySurface ?: placeholderSurface()
        try {
            c.setOutputSurface(target)
            rendering = displaySurface != null
            AppLog.i(TAG, if (rendering) "rendering to screen" else "rendering paused (no screen)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "setOutputSurface failed on ${c.name}; recreating decoder", t)
            val ps = params
            releaseLocked()
            if (ps != null) configureLocked(codecH265, ps, refeed = true)
        }
    }

    fun decode(buf: ByteBuffer, len: Int, h265: Boolean) {
        if (len <= 4 || len > MAX_FRAME) return
        if (scratch.size < len) scratch = ByteArray(len + len / 4)
        buf.position(0)
        buf.get(scratch, 0, len)
        // UxPlay marks access units it could not decrypt by setting the first byte to 1.
        if (scratch[0].toInt() == 1) return

        synchronized(lock) {
            val nals = NalParser.split(scratch, len, h265)
            if (nals.isEmpty()) return
            val isKey = NalParser.isKeyFrame(nals, h265)
            val ps = NalParser.parameterSets(scratch, nals, h265)
            if (isKey) {
                keyFrame = scratch.copyOf(len)
                keyFrameH265 = h265
            }
            if (ps != null && (!ps.sameAs(params) || codec == null || codecH265 != h265)) {
                params = ps
                configureLocked(h265, ps, refeed = false)
                needKeyFrame = true
            } else if (codec == null && params != null && keyFrame != null) {
                // Decoder was lost (error, failed surface switch): rebuild from the cache.
                configureLocked(h265, params!!, refeed = !isKey)
            }
            val c = codec ?: return
            if (needKeyFrame) {
                if (!isKey) return
                needKeyFrame = false
            }
            if (!queue(c, scratch, len)) {
                // A failed queue means the codec is in an error state: rebuild next frame.
                releaseLocked()
            }
        }
    }

    /** Intentionally does not reset decoder state; see class comment. */
    fun flush() {
        AppLog.d(TAG, "flush ignored (live stream)")
    }

    fun stop() = synchronized(lock) {
        releaseLocked()
        params = null
        keyFrame = null
        needKeyFrame = true
        _size.value = null
    }

    private fun queue(c: MediaCodec, data: ByteArray, len: Int): Boolean = try {
        val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            val input = c.getInputBuffer(index)
            if (input == null || input.capacity() < len) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                AppLog.w(TAG, "access unit of $len bytes does not fit the decoder input buffer")
            } else {
                input.clear()
                input.put(data, 0, len)
                c.queueInputBuffer(index, 0, len, System.nanoTime() / 1000, 0)
            }
        }
        // index < 0: decoder busy, drop this frame (live stream)
        true
    } catch (t: Throwable) {
        AppLog.w(TAG, "decoder input failed; rebuilding decoder", t)
        false
    }

    private fun configureLocked(h265: Boolean, ps: NalParser.ParameterSets, refeed: Boolean) {
        releaseLocked()
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val target = displaySurface ?: placeholderSurface()

        // Attempts from most to least demanding. Some TV-box decoders reject the
        // low-latency/vendor keys or an oversized "max" resolution.
        val attempts = buildList {
            val hw = selectDecoder(mime, preferHardware)
            add(Attempt(hw, maxSize = true, lowLatency = true))
            add(Attempt(hw, maxSize = false, lowLatency = false))
            val sw = selectDecoder(mime, hardware = false)
            if (sw != null && sw != hw) add(Attempt(sw, maxSize = false, lowLatency = false))
        }
        for (a in attempts) {
            val c = try {
                if (a.name != null) MediaCodec.createByCodecName(a.name) else MediaCodec.createDecoderByType(mime)
            } catch (t: Throwable) {
                AppLog.w(TAG, "cannot create decoder ${a.name ?: mime}", t); continue
            }
            try {
                c.configure(buildFormat(mime, h265, ps, c, a), target, null, 0)
                c.start()
            } catch (t: Throwable) {
                AppLog.w(TAG, "decoder ${c.name} rejected the stream (maxSize=${a.maxSize}, lowLatency=${a.lowLatency})", t)
                runCatching { c.release() }
                continue
            }
            codec = c
            codecH265 = h265
            rendering = displaySurface != null
            running = true
            framesRendered = 0
            _active.value = true
            AppLog.i(TAG, "decoder ${c.name} started (${if (h265) "H.265" else "H.264"}, " +
                "${hintedSize.width}x${hintedSize.height}, ${if (rendering) "on screen" else "off screen"})")
            drainThread = Thread({ drainLoop(c) }, "mirror-video-out").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            val kf = keyFrame
            if (refeed && kf != null && keyFrameH265 == h265) {
                if (queue(c, kf, kf.size)) needKeyFrame = false
            }
            return
        }
        AppLog.e(TAG, "no decoder could play the mirroring stream ($mime ${hintedSize.width}x${hintedSize.height})")
    }

    private data class Attempt(val name: String?, val maxSize: Boolean, val lowLatency: Boolean)

    private fun buildFormat(mime: String, h265: Boolean, ps: NalParser.ParameterSets, c: MediaCodec, a: Attempt) =
        MediaFormat.createVideoFormat(mime, hintedSize.width, hintedSize.height).apply {
            if (h265) {
                setByteBuffer("csd-0", ByteBuffer.wrap((ps.vps ?: ByteArray(0)) + ps.sps + ps.pps))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(ps.sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(ps.pps))
            }
            if (a.maxSize) {
                // Room for rotation without reconfiguring, but only within what the decoder supports.
                maxSupportedSize(c, mime)?.let { (w, h) ->
                    setInteger(MediaFormat.KEY_MAX_WIDTH, w)
                    setInteger(MediaFormat.KEY_MAX_HEIGHT, h)
                }
            }
            if (a.lowLatency) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
            }
        }

    private fun maxSupportedSize(c: MediaCodec, mime: String): Pair<Int, Int>? = runCatching {
        val caps = c.codecInfo.getCapabilitiesForType(mime).videoCapabilities
        val side = maxOf(hintedSize.width, hintedSize.height, 1920)
        when {
            caps.isSizeSupported(side, side) -> side to side
            caps.isSizeSupported(1920, 1080) && caps.isSizeSupported(1080, 1920) -> 1920 to 1920
            else -> null
        }
    }.getOrNull()

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { c.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US) } catch (_: Throwable) { break }
            when {
                idx >= 0 -> runCatching {
                    c.releaseOutputBuffer(idx, rendering)
                    if (rendering && framesRendered++ == 0L) AppLog.i(TAG, "first frame on screen")
                }
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
                // secure-only decoders cannot output to a normal surface
                .filterNot { it.name.endsWith(".secure") }
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
