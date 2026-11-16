package com.airplaytv.app

import android.hardware.display.DisplayManager
import android.view.Display
import com.airplaytv.airplay.ReceiverConfig
import com.airplaytv.core.AppLog
import com.airplaytv.core.AuthMode
import com.airplaytv.core.LocalNetwork
import com.airplaytv.core.ReceiverSettings
import com.airplaytv.core.ReceiverStatus
import com.airplaytv.core.ResolutionMode
import com.airplaytv.core.Session
import com.airplaytv.network.MdnsService
import com.airplaytv.network.RemoteCommand
import com.airplaytv.playback.MirrorVideoDecoder
import com.airplaytv.security.PolicyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Brings the receiver to the state the settings and the network call for:
 * (re)starts the native receiver and the Bonjour announcement when the network, the
 * device name or a protocol-relevant setting changes, and stops everything when no
 * permitted local network is available.
 */
class ReceiverController(private val g: AppGraph) {
    private val lifecycle = MutableStateFlow<ReceiverStatus>(ReceiverStatus.Stopped)
    private var job: Job? = null
    private var remoteJob: Job? = null
    private var runningKey: String? = null
    private val passwordVersion = MutableStateFlow(0)

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    val network: StateFlow<LocalNetwork?> get() = g.network.network

    /** Combined status shown by the UI and the notification. */
    val status: StateFlow<ReceiverStatus> = combine(lifecycle, g.receiver.runtime) { base, rt ->
        if (base is ReceiverStatus.Ready && rt.active && rt.client != null) {
            ReceiverStatus.Connected(base.port, Session(rt.client!!, rt.content))
        } else base
    }.stateIn(g.scope, SharingStarted.Eagerly, ReceiverStatus.Stopped)

    @Synchronized
    fun start() {
        if (job != null) return
        _serviceRunning.value = true
        lifecycle.value = ReceiverStatus.Starting
        g.network.start()
        job = g.scope.launch {
            combine(g.settings.settings, g.network.network, passwordVersion) { s, n, _ -> s to n }
                .collectLatest { (s, n) -> withContext(Dispatchers.IO) { apply(s, n) } }
        }
        remoteJob = g.scope.launch {
            g.receiver.runtime.collect { rt -> g.dacp.setSession(rt.remote?.dacpId, rt.remote?.activeRemote) }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        remoteJob?.cancel()
        remoteJob = null
        g.dacp.setSession(null, null)
        g.scope.launch(Dispatchers.IO) {
            synchronized(this@ReceiverController) {
                g.mdns.stop()
                g.receiver.stop()
                runningKey = null
            }
        }
        g.network.stop()
        lifecycle.value = ReceiverStatus.Stopped
        _serviceRunning.value = false
    }

    /** True when the current sender offered remote control (DACP). */
    val remoteControlAvailable: StateFlow<Boolean> = g.receiver.runtime
        .map { it.remote != null }
        .stateIn(g.scope, SharingStarted.Eagerly, false)

    /** Play/pause/next/previous on the sending device. Fire-and-forget. */
    fun remote(cmd: RemoteCommand) {
        g.scope.launch(Dispatchers.IO) { g.dacp.send(cmd) }
    }

    fun disconnectAll() {
        g.policy.clearPin()
        g.receiver.disconnectAll()
    }

    /** Stores a new fixed password (encrypted) and restarts the receiver with it. */
    fun setPassword(password: String) {
        val bytes = password.toByteArray(Charsets.UTF_8)
        require(bytes.size in 4..64)
        g.secrets.write(SECRET_PASSWORD, bytes)
        bytes.fill(0)
        passwordVersion.value++
    }

    fun hasPassword(): Boolean = g.secrets.exists(SECRET_PASSWORD)

    private fun apply(s: ReceiverSettings, n: LocalNetwork?) { synchronized(this) {
        g.policy.config = PolicyConfig(
            confirmNewDevices = s.confirmNewDevices,
            pinPairing = s.authMode == AuthMode.PIN_PAIRING,
            showPins = s.authMode == AuthMode.PIN_PAIRING || s.authMode == AuthMode.ONE_TIME_CODE,
        )
        g.playback.configure(s.hardwareDecoding, s.followSenderVolume)

        if (n == null) {
            g.subnets.update(emptyList())
            if (runningKey != null) {
                AppLog.w(TAG, "local network lost; receiver paused")
                g.mdns.stop()
                g.receiver.stop()
                runningKey = null
            }
            lifecycle.value = ReceiverStatus.NoNetwork
            return
        }
        g.subnets.update(n.addresses)

        val password = if (s.authMode == AuthMode.PASSWORD) g.secrets.read(SECRET_PASSWORD) else null
        if (s.authMode == AuthMode.PASSWORD && password == null) {
            lifecycle.value = ReceiverStatus.Error("Задайте пароль в настройках")
            stopLocked()
            return
        }
        val (w, h) = resolution(s.resolution)
        val h265 = s.allowH265 && MirrorVideoDecoder.hasHardwareHevc()
        val key = listOf(
            s.deviceName, s.authMode, s.resolution, s.maxFps, h265, s.hlsVideo,
            n.interfaceName, n.primaryIpv4, password?.let { digest(it) }, passwordVersion.value,
        ).joinToString("|")
        if (key == runningKey) {
            password?.fill(0)
            return
        }

        lifecycle.value = ReceiverStatus.Starting
        stopLocked()
        val result = withIdentityKey { keyPath ->
            g.receiver.start(
                ReceiverConfig(
                    name = s.deviceName,
                    hwAddr = deviceId(),
                    keyFile = keyPath,
                    authMode = s.authMode.nativeValue,
                    password = password,
                    width = w, height = h, fps = s.maxFps,
                    hls = s.hlsVideo, h265 = h265,
                ),
            )
        }
        password?.fill(0)
        val records = result.getOrElse {
            lifecycle.value = ReceiverStatus.Error("Не удалось запустить приёмник")
            scheduleRetry()
            return
        }
        try {
            g.mdns.start(n, hostLabel(), records.map { MdnsService(it.type, it.name, it.port, it.txt) })
        } catch (t: Throwable) {
            AppLog.e(TAG, "mDNS announcement failed", t)
            g.receiver.stop()
            lifecycle.value = ReceiverStatus.Error("Не удалось объявить приёмник в сети")
            scheduleRetry()
            return
        }
        runningKey = key
        lifecycle.value = ReceiverStatus.Ready(records.first().port)
    } }

    private fun stopLocked() {
        g.mdns.stop()
        g.receiver.stop()
        runningKey = null
    }

    /** Transient failures (port busy, network flapping): try again shortly. */
    private fun scheduleRetry() {
        g.scope.launch {
            delay(5_000)
            if (job != null && runningKey == null) passwordVersion.value++
        }
    }

    /**
     * UxPlay keeps its Ed25519 identity in a PEM file. At rest we only keep it encrypted
     * (SecretStore); a plaintext copy exists in app-private no-backup storage just for the
     * duration of native initialisation, then it is re-encrypted and deleted.
     */
    private fun <T> withIdentityKey(block: (String) -> T): T {
        val dir = File(g.appContext.noBackupFilesDir, "rt").apply { mkdirs() }
        val f = File(dir, "id.pem")
        g.secrets.read(SECRET_KEY)?.let { bytes -> f.writeBytes(bytes); bytes.fill(0) }
        f.setReadable(false, false); f.setReadable(true, true)
        try {
            return block(f.path)
        } finally {
            if (f.exists()) {
                val bytes = f.readBytes()
                if (bytes.isNotEmpty()) g.secrets.write(SECRET_KEY, bytes)
                bytes.fill(0)
                f.writeBytes(ByteArray(f.length().toInt()))
                f.delete()
            }
        }
    }

    /** Stable random device id (locally administered unicast MAC); Android hides the real MAC. */
    private fun deviceId(): ByteArray {
        g.secrets.read(SECRET_DEVICE_ID)?.takeIf { it.size == 6 }?.let { return it }
        val id = ByteArray(6).also { SecureRandom().nextBytes(it) }
        id[0] = ((id[0].toInt() and 0xFC) or 0x02).toByte()
        g.secrets.write(SECRET_DEVICE_ID, id)
        return id
    }

    private fun hostLabel(): String =
        "AirPlayTV-" + deviceId().takeLast(2).joinToString("") { "%02X".format(it) }

    private fun resolution(mode: ResolutionMode): Pair<Int, Int> {
        if (mode != ResolutionMode.AUTO) return mode.width to mode.height
        val display = g.appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        val m = display?.mode
        val dw = maxOf(m?.physicalWidth ?: 1920, m?.physicalHeight ?: 1080)
        val max = MirrorVideoDecoder.maxAvcSize()
        return when {
            dw >= 3840 && max.width >= 3840 -> 3840 to 2160
            dw <= 1280 -> 1280 to 720
            else -> 1920 to 1080
        }
    }

    private fun digest(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ReceiverController"
        const val SECRET_PASSWORD = "password"
        const val SECRET_KEY = "identity_key"
        const val SECRET_DEVICE_ID = "device_id"
    }
}
