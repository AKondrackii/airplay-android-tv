package com.airplaytv.security

import com.airplaytv.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A device the user allowed. Two levels of trust exist and are shown honestly in the UI:
 *  - [publicKey] != null: the device completed AirPlay PIN pairing; its Ed25519 key is
 *    verified cryptographically on every connection (pair-verify).
 *  - [publicKey] == null: the user confirmed it on screen; it is recognised only by the
 *    deviceID it reports, which the protocol does not authenticate.
 */
data class TrustedDevice(
    val deviceId: String,
    val name: String,
    val publicKey: String?,
    val addedAt: Long,
)

/** Persists trusted devices encrypted through [SecretStore]. Thread-safe. */
class TrustedDeviceStore(private val secrets: SecretStore) : TrustRegistry {
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<TrustedDevice>> = _devices.asStateFlow()

    @Synchronized
    override fun add(device: TrustedDevice) {
        val list = _devices.value.filterNot { it.deviceId.equals(device.deviceId, ignoreCase = true) } + device
        save(list.takeLast(MAX_DEVICES))
    }

    @Synchronized
    fun remove(deviceId: String) = save(_devices.value.filterNot { it.deviceId == deviceId })

    @Synchronized
    fun clear() = save(emptyList())

    override fun isTrustedId(deviceId: String?): Boolean =
        deviceId != null && _devices.value.any { it.deviceId.equals(deviceId, ignoreCase = true) }

    override fun isTrustedKey(publicKey: String): Boolean = _devices.value.any { it.publicKey == publicKey }

    private fun save(list: List<TrustedDevice>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.deviceId); put("name", it.name); put("added", it.addedAt)
                if (it.publicKey != null) put("pk", it.publicKey)
            })
        }
        secrets.write(FILE, arr.toString().toByteArray(Charsets.UTF_8))
        _devices.value = list
    }

    private fun load(): List<TrustedDevice> {
        val raw = secrets.read(FILE) ?: return emptyList()
        return try {
            val arr = JSONArray(String(raw, Charsets.UTF_8))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TrustedDevice(o.getString("id"), o.optString("name"), o.optString("pk").ifEmpty { null }, o.optLong("added"))
            }
        } catch (t: Throwable) {
            AppLog.e("TrustedDevices", "corrupt trusted device list, resetting", t)
            emptyList()
        }
    }

    private companion object {
        const val FILE = "trusted_devices"
        const val MAX_DEVICES = 32
    }
}
