package com.airplaytv.network

import android.content.Context
import android.net.wifi.WifiManager
import com.airplaytv.core.AppLog
import com.airplaytv.core.LocalNetwork
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** A Bonjour service to announce. */
data class MdnsService(val type: String, val name: String, val port: Int, val txt: Map<String, String>)

/**
 * Announces the receiver via multicast DNS (Bonjour) so Apple devices list it.
 *
 * JmDNS is bound to the IPv4 address of the allowed interface only, so the receiver is
 * never announced on other networks (mobile data, VPN, a second NIC). A multicast lock
 * is held while announcing, because many Wi-Fi drivers drop multicast otherwise.
 *
 * Blocking calls; invoke from a background thread.
 */
class MdnsAdvertiser(context: Context) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null

    @Synchronized
    fun start(network: LocalNetwork, hostLabel: String, services: List<MdnsService>) {
        stop()
        val ip = network.addresses.firstOrNull { it.isIpv4 }?.address
            ?: throw IllegalStateException("no IPv4 address on ${network.interfaceName}")
        multicastLock = wifi?.createMulticastLock("airplaytv-mdns")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val addr = InetAddress.getByAddress(ip)
        val dns = JmDNS.create(addr, hostLabel)
        jmdns = dns
        for (s in services) {
            val info = ServiceInfo.create("${s.type}.local.", s.name, s.port, 0, 0, s.txt)
            dns.registerService(info)
            AppLog.i(TAG, "announced ${s.type} on ${network.interfaceName}")
        }
    }

    @Synchronized
    fun stop() {
        jmdns?.let { dns ->
            runCatching { dns.unregisterAllServices() }   // sends goodbye packets
            runCatching { dns.close() }
        }
        jmdns = null
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    val isRunning: Boolean @Synchronized get() = jmdns != null

    private companion object { const val TAG = "MdnsAdvertiser" }
}
