package com.airplaytv.core

/** What the sender is currently streaming. */
enum class ContentType { NONE, MIRRORING, VIDEO, AUDIO }

/** Network transport the receiver is bound to. Mobile data and VPN are never used. */
enum class Transport { WIFI, ETHERNET }

/** One IP address of the active local interface together with its prefix length. */
data class InterfaceAddress(val address: ByteArray, val prefixLength: Int) {
    val isIpv4: Boolean get() = address.size == 4
    override fun equals(other: Any?) =
        other is InterfaceAddress && address.contentEquals(other.address) && prefixLength == other.prefixLength
    override fun hashCode() = address.contentHashCode() * 31 + prefixLength
}

/** The local network the receiver is allowed to serve. */
data class LocalNetwork(
    val interfaceName: String,
    val transport: Transport,
    val addresses: List<InterfaceAddress>,
    /** Null when the SSID is not available (no location permission, or Ethernet). */
    val ssid: String?,
) {
    val primaryIpv4: String?
        get() = addresses.firstOrNull { it.isIpv4 }?.address?.joinToString(".") { (it.toInt() and 0xFF).toString() }
}

data class ClientInfo(val deviceId: String?, val model: String?, val name: String?) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: model ?: "Apple device"
}

data class Session(
    val client: ClientInfo,
    val content: ContentType,
    val startedAt: Long = System.currentTimeMillis(),
)

sealed interface ReceiverStatus {
    data object Stopped : ReceiverStatus
    data object Starting : ReceiverStatus
    /** No permitted local network (e.g. only mobile data / VPN, or disconnected). */
    data object NoNetwork : ReceiverStatus
    data class Ready(val port: Int) : ReceiverStatus
    data class Connected(val port: Int, val session: Session) : ReceiverStatus
    data class Error(val message: String) : ReceiverStatus
}

/** Interactive requests the receiver needs the user to see or answer on the TV. */
sealed interface UserPrompt {
    /** PIN / one-time code the sender must type. */
    data class ShowPin(val pin: String, val client: ClientInfo?, val expiresAt: Long = 0) : UserPrompt
    /** Asks whether to admit a new device. Answer through [ConnectionPolicy.answerPrompt]. */
    data class ConfirmClient(val id: Long, val client: ClientInfo, val expiresAt: Long) : UserPrompt
}

data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverArt: ByteArray? = null,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
) {
    override fun equals(other: Any?): Boolean = other is NowPlaying && title == other.title &&
        artist == other.artist && album == other.album && positionSec == other.positionSec &&
        durationSec == other.durationSec && (coverArt?.contentEquals(other.coverArt) ?: (other.coverArt == null))
    override fun hashCode(): Int = listOf(title, artist, album, positionSec, durationSec).hashCode()
}
