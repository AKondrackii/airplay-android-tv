package com.airplaytv.security

import com.airplaytv.core.AppLog
import com.airplaytv.core.ClientInfo
import com.airplaytv.core.ConnectionPolicy
import com.airplaytv.core.UserPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Persistence of trusted devices (implemented by [TrustedDeviceStore]; faked in tests). */
interface TrustRegistry {
    fun isTrustedId(deviceId: String?): Boolean
    fun isTrustedKey(publicKey: String): Boolean
    fun add(device: TrustedDevice)
}

/** Settings snapshot relevant to admission decisions. */
data class PolicyConfig(
    val confirmNewDevices: Boolean = false,
    /** True when AuthMode.PIN_PAIRING is active: pair-verify must present a trusted key. */
    val pinPairing: Boolean = false,
    /** True for the modes that show a code on the TV (PIN pairing, one-time code). */
    val showPins: Boolean = false,
    val pinDisplayMillis: Long = 60_000,
    val confirmTimeoutMillis: Long = 25_000,
)

/**
 * The receiver's security policy:
 *  - network filter + per-host rate limit at TCP accept,
 *  - optional on-screen confirmation of unknown devices (blocking the RTSP SETUP),
 *  - trusted-device registry (PIN-paired keys and user-confirmed device IDs).
 */
class ReceiverPolicy(
    private val subnets: SubnetFilter,
    private val trust: TrustRegistry,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ConnectionPolicy {

    @Volatile var config = PolicyConfig()

    private val _prompt = MutableStateFlow<UserPrompt?>(null)
    /** What the TV must currently show (PIN or confirmation), observed by the UI. */
    val prompt: StateFlow<UserPrompt?> = _prompt.asStateFlow()

    private val promptIds = AtomicLong(1)
    private val lock = Any()
    private var pending: Pair<Long, CompletableFuture<Decision>>? = null
    private val recentlyDenied = RateLimiter(maxPerWindow = 0, windowMillis = 1, blockMillis = 60_000, clock = clock)

    data class Decision(val allow: Boolean, val remember: Boolean)

    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "policy-timer").apply { isDaemon = true }
    }

    override fun acceptConnection(local: ByteArray, remote: ByteArray): Boolean {
        val key = remote.joinToString(".") { (it.toInt() and 0xFF).toString() }
        if (!subnets.isAllowed(local, remote)) {
            AppLog.w(TAG, "rejected connection from outside the allowed local network")
            return false
        }
        if (SubnetFilter.isLoopback(remote)) return true   // local HLS player, not rate limited
        if (!rateLimiter.tryAcquire(key)) {
            AppLog.w(TAG, "rate limit: too many connections from one host")
            return false
        }
        return true
    }

    override fun admitClient(client: ClientInfo): Boolean {
        val cfg = config
        if (trust.isTrustedId(client.deviceId)) return true.also { clearPin() }
        if (!cfg.confirmNewDevices) return true.also { clearPin() }

        val idKey = client.deviceId ?: client.displayName
        if (recentlyDenied.isBlocked(idKey)) {
            AppLog.i(TAG, "auto-denied recently rejected device")
            return false
        }

        val future = CompletableFuture<Decision>()
        val id = promptIds.getAndIncrement()
        synchronized(lock) {
            if (pending != null) {
                AppLog.w(TAG, "another confirmation is pending; denying concurrent request")
                return false
            }
            pending = id to future
        }
        _prompt.value = UserPrompt.ConfirmClient(id, client, clock() + cfg.confirmTimeoutMillis)
        val decision = try {
            future.get(cfg.confirmTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            AppLog.i(TAG, "confirmation timed out")
            Decision(allow = false, remember = false)
        } catch (t: Throwable) {
            Decision(allow = false, remember = false)
        } finally {
            synchronized(lock) { if (pending?.first == id) pending = null }
            if ((_prompt.value as? UserPrompt.ConfirmClient)?.id == id) _prompt.value = null
        }
        if (!decision.allow) {
            recentlyDenied.block(idKey)
            return false
        }
        if (decision.remember && client.deviceId != null) {
            trust.add(TrustedDevice(client.deviceId!!, client.displayName, null, clock()))
        }
        clearPin()
        return true
    }

    /** Called by the UI. Unknown or stale ids are ignored. */
    fun answerPrompt(id: Long, allow: Boolean, remember: Boolean) {
        val f = synchronized(lock) { pending?.takeIf { it.first == id }?.second } ?: return
        f.complete(Decision(allow, remember))
    }

    override fun onClientPaired(deviceId: String, publicKey: String, name: String) {
        trust.add(TrustedDevice(deviceId, name, publicKey, clock()))
        clearPin()
        AppLog.i(TAG, "device paired with PIN and added to trusted devices")
    }

    override fun isPairedKeyTrusted(publicKey: String): Boolean = trust.isTrustedKey(publicKey)

    override fun onPinDisplayed(pin: String) {
        val cfg = config
        // Never let a LAN host pop up a code when no code-based mode is enabled.
        if (!cfg.showPins || pin.length != 4 || !pin.all(Char::isDigit)) return
        val prompt = UserPrompt.ShowPin(pin, null, clock() + cfg.pinDisplayMillis)
        _prompt.value = prompt
        timer.schedule({ if (_prompt.value == prompt) _prompt.value = null }, cfg.pinDisplayMillis, TimeUnit.MILLISECONDS)
    }

    fun clearPin() {
        if (_prompt.value is UserPrompt.ShowPin) _prompt.value = null
    }

    fun resetRateLimits() = rateLimiter.clear()

    private companion object { const val TAG = "ReceiverPolicy" }
}
