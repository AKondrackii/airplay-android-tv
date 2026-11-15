package com.airplaytv.airplay

import com.airplaytv.core.AppLog
import com.airplaytv.core.ClientInfo
import com.airplaytv.core.ConnectionPolicy
import com.airplaytv.core.ContentType
import com.airplaytv.core.MediaSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** A Bonjour service the native receiver wants published. */
data class ServiceRecord(val type: String, val name: String, val port: Int, val txt: Map<String, String>)

data class ReceiverConfig(
    val name: String,
    /** Stable 6-byte device id (locally administered MAC; Android hides the real one). */
    val hwAddr: ByteArray,
    /** Path of the Ed25519 identity key file (decrypted copy, see SecretStore). */
    val keyFile: String,
    val authMode: Int,
    val fixedPin: Int = 0,
    val password: ByteArray? = null,
    val width: Int,
    val height: Int,
    val fps: Int,
    val hls: Boolean,
    val h265: Boolean,
    val verboseLog: Boolean = false,
)

/** Live state of the protocol layer (independent from the UI lifecycle). */
data class ReceiverRuntime(
    val running: Boolean = false,
    val port: Int = 0,
    val openConnections: Int = 0,
    val client: ClientInfo? = null,
    val mirroring: Boolean = false,
    val hls: Boolean = false,
    val audio: Boolean = false,
    val audioIsMedia: Boolean = false,
) {
    val content: ContentType
        get() = when {
            mirroring -> ContentType.MIRRORING
            hls -> ContentType.VIDEO
            audio && audioIsMedia -> ContentType.AUDIO
            audio -> ContentType.AUDIO
            else -> ContentType.NONE
        }
    val active: Boolean get() = client != null && openConnections > 0
}

/**
 * Owns the native UxPlay receiver. One instance per process.
 *
 * start()/stop()/resetConnections() are serialized on a private thread because the
 * native calls join UxPlay threads and must never run on a native callback thread.
 */
class AirPlayReceiver(
    private val policy: ConnectionPolicy,
    private val sink: MediaSink,
) {
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "airplay-control") }
    private val _runtime = MutableStateFlow(ReceiverRuntime())
    val runtime: StateFlow<ReceiverRuntime> = _runtime.asStateFlow()

    @Volatile private var handle = 0L
    private var services: List<ServiceRecord> = emptyList()

    private val events = object : NativeCallbacks.ReceiverEvents {
        override fun onConnectionCountChanged(delta: Int) = _runtime.update {
            val n = (it.openConnections + delta).coerceAtLeast(0)
            if (n == 0) it.copy(openConnections = 0, client = null, mirroring = false, hls = false, audio = false)
            else it.copy(openConnections = n)
        }

        override fun onConnectionLost(reason: Int) {
            AppLog.w(TAG, "connection lost (reason $reason), restarting listener")
            sink.onVideoStopped(); sink.onAudioStopped(); sink.onHlsStop()
            control.execute { resetLocked() }
        }

        override fun onClientAdmitted(client: ClientInfo) = _runtime.update { it.copy(client = client) }
        override fun onMirroring(running: Boolean) = _runtime.update { it.copy(mirroring = running) }
        override fun onAudioStream(compressionType: Int, isMedia: Boolean, usingScreen: Boolean) =
            _runtime.update { it.copy(audio = true, audioIsMedia = isMedia && !usingScreen) }
        override fun onHlsStarted() = _runtime.update { it.copy(hls = true) }
        override fun onHlsStopped() = _runtime.update { it.copy(hls = false) }
        override fun onStreamsReset(type: Int) {
            // RESET_TYPE_RTP_SHUTDOWN (1) / NOHOLD (0): the mirroring/audio stream ended.
            if (type == 0 || type == 1) {
                sink.onAudioStopped()
                _runtime.update { it.copy(mirroring = false, audio = false) }
            }
        }
    }

    /** Starts the receiver; returns the Bonjour records to publish. Blocking. */
    fun start(config: ReceiverConfig): Result<List<ServiceRecord>> = runOnControl {
        stopLocked()
        val callbacks = NativeCallbacks(policy, sink, events)
        val h = NativeBridge.nativeCreate(
            callbacks, config.name, config.hwAddr, config.keyFile, config.authMode, config.fixedPin,
            config.password, config.width, config.height, config.fps, config.hls, config.h265,
            if (config.verboseLog) 7 else 6,
        )
        config.password?.fill(0)
        if (h == 0L) error("native receiver could not be created")
        val port = NativeBridge.nativeStart(h, 7000)
            .takeIf { it > 0 } ?: NativeBridge.nativeStart(h, 0) // 7000 may be taken; let the OS pick
        if (port <= 0) {
            NativeBridge.nativeDestroy(h)
            error("listener could not start ($port)")
        }
        handle = h
        services = listOfNotNull(
            readService(h, NativeBridge.SERVICE_AIRPLAY),
            readService(h, NativeBridge.SERVICE_RAOP),
        )
        _runtime.value = ReceiverRuntime(running = true, port = port)
        AppLog.i(TAG, "receiver listening on port $port")
        services
    }

    fun stop() {
        runOnControl { stopLocked() }
    }

    /** Ends every session (used by "Disconnect" / "End all sessions"). Non-blocking. */
    fun disconnectAll() {
        sink.onVideoStopped(); sink.onAudioStopped(); sink.onHlsStop()
        control.execute { resetLocked() }
    }

    private fun resetLocked() {
        val h = handle
        if (h == 0L) return
        val port = NativeBridge.nativeResetConnections(h)
        if (port < 0) AppLog.e(TAG, "listener restart failed ($port)")
        _runtime.update { ReceiverRuntime(running = port > 0, port = if (port > 0) port else it.port) }
    }

    private fun stopLocked() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        sink.onVideoStopped(); sink.onAudioStopped(); sink.onHlsStop()
        NativeBridge.nativeDestroy(h)
        services = emptyList()
        _runtime.value = ReceiverRuntime()
        AppLog.i(TAG, "receiver stopped")
    }

    private fun readService(h: Long, which: Int): ServiceRecord? {
        val a = NativeBridge.nativeGetService(h, which) ?: return null
        if (a.size < 3) return null
        val txt = LinkedHashMap<String, String>()
        var i = 3
        while (i + 1 < a.size) { txt[a[i]] = a[i + 1]; i += 2 }
        return ServiceRecord(type = a[1], name = a[0], port = a[2].toInt(), txt = txt)
    }

    private fun <T> runOnControl(block: () -> T): Result<T> =
        runCatching { control.submit<T> { block() }.get() }
            .onFailure { AppLog.e(TAG, "receiver control failed", it.cause ?: it) }
            .recoverCatching { throw (it.cause ?: it) }

    private companion object { const val TAG = "AirPlayReceiver" }
}
