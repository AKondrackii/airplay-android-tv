package com.airplaytv.network

import com.airplaytv.core.AppLog
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/** Remote-control commands an AirPlay audio sender understands (DACP). */
enum class RemoteCommand(val path: String) {
    PLAY_PAUSE("playpause"), NEXT("nextitem"), PREVIOUS("previtem"),
}

/**
 * Sends play/pause/next/previous to the device that is streaming audio, using DACP:
 * the sender announces `iTunes_Ctrl_<DACP-ID>._dacp._tcp` and accepts
 * `GET /ctrl-int/1/<command>` with the `Active-Remote` token it gave the receiver.
 * The command reaches the sender's system "Now Playing" controls, so it works for any
 * app that plays through them (Apple Music, Yandex Music, podcasts...).
 *
 * Requests only go to an address inside the allowed local network ([isLocalPeer]).
 * Blocking; call from a background thread.
 */
class DacpClient(
    private val mdns: MdnsAdvertiser,
    private val isLocalPeer: (ByteArray) -> Boolean,
) {
    private data class Endpoint(val dacpId: String, val token: String, val host: ByteArray, val port: Int)

    @Volatile private var dacpId: String? = null
    @Volatile private var token: String? = null
    @Volatile private var endpoint: Endpoint? = null

    val available: Boolean get() = dacpId != null

    @Synchronized
    fun setSession(dacpId: String?, activeRemote: String?) {
        if (dacpId == this.dacpId && activeRemote == token) return
        this.dacpId = dacpId
        this.token = activeRemote
        endpoint = null
    }

    /** Returns true if the sender accepted the command. */
    fun send(cmd: RemoteCommand): Boolean {
        val ep = endpoint ?: resolve() ?: return false
        if (request(ep, cmd)) return true
        // The sender may have changed port (app restarted): resolve again once.
        endpoint = null
        val again = resolve() ?: return false
        return request(again, cmd)
    }

    private fun resolve(): Endpoint? {
        val id = dacpId ?: return null
        val tok = token ?: return null
        val found = mdns.resolve("_dacp._tcp", "iTunes_Ctrl_$id", 3_000)
        if (found == null) {
            AppLog.w(TAG, "remote control endpoint of the sender not found on the network")
            return null
        }
        val (host, port) = found
        if (port !in 1..65535 || !isLocalPeer(host)) {
            AppLog.w(TAG, "ignoring remote control endpoint outside the local network")
            return null
        }
        return Endpoint(id, tok, host, port).also { endpoint = it }
    }

    private fun request(ep: Endpoint, cmd: RemoteCommand): Boolean {
        val ip = InetAddress.getByAddress(ep.host) as? Inet4Address ?: return false
        val conn = URL("http", ip.hostAddress, ep.port, "/ctrl-int/1/${cmd.path}").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Active-Remote", ep.token)
            conn.setRequestProperty("Host", "iTunes_Ctrl_${ep.dacpId}.local.")
            val code = conn.responseCode
            (code in 200..299).also { if (!it) AppLog.w(TAG, "sender rejected ${cmd.path} ($code)") }
        } catch (t: Throwable) {
            AppLog.w(TAG, "remote command ${cmd.path} failed", t)
            false
        } finally {
            conn.disconnect()
        }
    }

    private companion object { const val TAG = "DacpClient" }
}
