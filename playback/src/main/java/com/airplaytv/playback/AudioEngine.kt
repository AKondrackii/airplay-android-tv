package com.airplaytv.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.airplaytv.core.AppLog
import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * AirPlay audio output: 44.1 kHz stereo 16-bit.
 *  - ALAC (music) arrives already decoded to PCM by the native Apple ALAC decoder.
 *  - AAC-ELD (mirroring) and AAC-LC (some apps) are decoded with MediaCodec.
 *
 * Called from the native audio thread only, except volume/stop which are thread-safe.
 */
class AudioEngine(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    @Volatile var followSenderVolume = true

    private val lock = Any()
    private var track: AudioTrack? = null
    private var aac: MediaCodec? = null
    private var aacType = 0
    private var focusRequest: AudioFocusRequest? = null
    private var pcmScratch = ByteArray(16 * 1024)
    @Volatile private var gain = 1f

    fun onFormat(compressionType: Int) = synchronized(lock) {
        ensureTrack()
        if (compressionType == CT_AAC_LC || compressionType == CT_AAC_ELD) {
            if (aac == null || aacType != compressionType) startAac(compressionType)
        } else {
            releaseAac()
        }
    }

    fun writePcm(buf: ByteBuffer, len: Int) {
        synchronized(lock) {
            val t = ensureTrack() ?: return
            if (len <= 0 || len > buf.capacity()) return
            buf.position(0)
            buf.limit(len)
            t.write(buf, len, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun decodeAac(buf: ByteBuffer, len: Int, compressionType: Int) = synchronized(lock) {
        if (aac == null || aacType != compressionType) startAac(compressionType)
        val c = aac ?: return
        val t = ensureTrack() ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val input = c.getInputBuffer(idx)!!
                input.clear()
                if (len > input.capacity()) {
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                } else {
                    buf.position(0); buf.limit(len)
                    input.put(buf)
                    c.queueInputBuffer(idx, 0, len, System.nanoTime() / 1000, 0)
                }
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val out = c.dequeueOutputBuffer(info, 0)
                if (out < 0) break
                val ob = c.getOutputBuffer(out)
                if (ob != null && info.size > 0) {
                    if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.get(pcmScratch, 0, info.size)
                    t.write(pcmScratch, 0, info.size)
                }
                c.releaseOutputBuffer(out, false)
            }
        } catch (e: Throwable) {
            AppLog.w(TAG, "AAC decode error, restarting decoder", e)
            releaseAac()
        }
    }

    fun flush() {
        synchronized(lock) {
            track?.let { runCatching { it.pause(); it.flush(); it.play() } }
            runCatching { aac?.flush() }
        }
    }

    /** AirPlay volume in dB (-144 = mute, -30..0). */
    fun setVolumeDb(db: Float) {
        if (!followSenderVolume) return
        gain = when {
            db <= -144f -> 0f
            db >= 0f -> 1f
            else -> 10f.pow(db.coerceAtLeast(-30f) / 20f)
        }
        synchronized(lock) { track?.setVolume(gain) }
    }

    fun stop() = synchronized(lock) {
        releaseAac()
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        abandonFocus()
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        // ~150 ms of buffering absorbs network jitter without adding noticeable lip-sync delay.
        val size = maxOf(min * 2, SAMPLE_RATE * 4 * 150 / 1000)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Throwable) {
            AppLog.e(TAG, "AudioTrack creation failed", e); return null
        }
        t.setVolume(gain)
        t.play()
        track = t
        requestFocus()
        return t
    }

    private fun startAac(ct: Int) {
        releaseAac()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            val csd = if (ct == CT_AAC_ELD) byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
                      else byteArrayOf(0x12, 0x10)
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (ct == CT_AAC_ELD) setInteger(MediaFormat.KEY_AAC_PROFILE, 39) // AACObjectELD
        }
        try {
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            aac = c
            aacType = ct
        } catch (e: Throwable) {
            AppLog.e(TAG, "AAC decoder unavailable (ct=$ct)", e)
        }
    }

    private fun releaseAac() {
        aac?.let { runCatching { it.stop() }; runCatching { it.release() } }
        aac = null
        aacType = 0
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }  // a receiver keeps playing what the sender sends
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 44_100
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
