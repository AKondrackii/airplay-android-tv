package com.airplaytv.core

import java.nio.ByteBuffer

/**
 * Security decisions the AirPlay layer delegates to the security module.
 * All methods are called from native receiver threads and must be thread-safe.
 */
interface ConnectionPolicy {
    /** Called right after TCP accept(), before any byte is parsed. Raw 4/16-byte addresses. */
    fun acceptConnection(local: ByteArray, remote: ByteArray): Boolean

    /**
     * Called when a sender sets up a session. May block (bounded) while the user confirms.
     * Returning false makes the receiver answer 401 and drop the session.
     */
    fun admitClient(client: ClientInfo): Boolean

    /** A sender completed PIN pairing; its Ed25519 public key identifies it from now on. */
    fun onClientPaired(deviceId: String, publicKey: String, name: String)

    /** Pair-verify of a returning sender: is this public key still trusted? */
    fun isPairedKeyTrusted(publicKey: String): Boolean

    fun onPinDisplayed(pin: String)
}

/**
 * Media output the AirPlay layer feeds. Implemented by the playback module.
 * Buffers passed in are only valid for the duration of the call.
 */
interface MediaSink {
    fun onVideoFormat(h265: Boolean, width: Int, height: Int)
    fun onVideoData(data: ByteBuffer, length: Int, h265: Boolean)
    fun onVideoFlush()
    fun onVideoStopped()

    /** ct: 2 = ALAC (delivered as PCM), 4 = AAC-LC, 8 = AAC-ELD. */
    fun onAudioFormat(compressionType: Int, framesPerPacket: Int)
    fun onAudioPcm(pcm: ByteBuffer, length: Int)
    fun onAudioCompressed(data: ByteBuffer, length: Int, compressionType: Int)
    fun onAudioFlush()
    /** AirPlay volume in dB: -144 = mute, otherwise -30..0. */
    fun onAudioVolume(volumeDb: Float)
    fun onAudioStopped()

    fun onMetadata(dmap: ByteArray)
    fun onCoverArt(jpeg: ByteArray)
    fun onProgress(startRtp: Long, currentRtp: Long, endRtp: Long)

    // AirPlay video (HLS URL casting)
    fun onHlsPlay(url: String, startPositionSec: Float)
    fun onHlsSeek(positionSec: Float)
    fun onHlsRate(rate: Float)
    fun onHlsStop()
    /** [duration, position, rate, readyToPlay, bufferEmpty, seekStart, seekDuration] or null. */
    fun hlsPlaybackInfo(): DoubleArray?
}
