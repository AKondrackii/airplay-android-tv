package com.airplaytv.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.airplaytv.core.AppLog
import com.airplaytv.core.InterfaceAddress
import com.airplaytv.core.LocalNetwork
import com.airplaytv.core.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Tracks the single local network the receiver may serve.
 *
 * Only Wi-Fi and Ethernet networks are considered; mobile data, VPN, Bluetooth tethering
 * and anything else are ignored. If several qualify, Ethernet wins. The published value
 * is debounced so short Wi-Fi roaming blips do not restart the receiver.
 */
class NetworkMonitor(context: Context, scope: CoroutineScope) {
    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    private data class Tracked(val caps: NetworkCapabilities?, val lp: LinkProperties?)
    private val tracked = LinkedHashMap<Network, Tracked>()
    private val raw = MutableStateFlow<LocalNetwork?>(null)

    @OptIn(FlowPreview::class)
    val network: StateFlow<LocalNetwork?> = raw
        .debounce(1_500)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network) { it }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            update(network) { it.copy(caps = caps) }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
            update(network) { it.copy(lp = lp) }
        override fun onLost(network: Network) {
            synchronized(tracked) { tracked.remove(network) }
            recompute()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            // A LAN without internet access is still a perfectly good AirPlay network.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            AppLog.e(TAG, "cannot register network callback", t)
        }
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        synchronized(tracked) { tracked.clear() }
        raw.value = null
    }

    private fun update(network: Network, f: (Tracked) -> Tracked) {
        synchronized(tracked) {
            val cur = tracked[network] ?: Tracked(cm.getNetworkCapabilities(network), cm.getLinkProperties(network))
            tracked[network] = f(cur)
        }
        recompute()
    }

    private fun recompute() {
        val candidates = synchronized(tracked) { tracked.values.toList() }
            .mapNotNull { t -> toLocalNetwork(t.caps ?: return@mapNotNull null, t.lp ?: return@mapNotNull null) }
        raw.value = candidates.minByOrNull { if (it.transport == Transport.ETHERNET) 0 else 1 }
    }

    private fun toLocalNetwork(caps: NetworkCapabilities, lp: LinkProperties): LocalNetwork? {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            else -> return null
        }
        val iface = lp.interfaceName ?: return null
        val addrs = lp.linkAddresses.mapNotNull { la ->
            val a = la.address
            when {
                a is Inet4Address && !a.isLoopbackAddress -> InterfaceAddress(a.address, la.prefixLength)
                a is Inet6Address && !a.isLoopbackAddress && !a.isMulticastAddress ->
                    InterfaceAddress(a.address, la.prefixLength)
                else -> null
            }
        }
        if (addrs.none { it.isIpv4 }) return null   // AirPlay senders reach receivers over IPv4 in practice
        val ssid = if (transport == Transport.WIFI) readSsid(caps) else null
        return LocalNetwork(iface, transport, addrs, ssid)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readSsid(caps: NetworkCapabilities): String? {
        // Android 10+ redacts the SSID unless the app holds location permission; we do
        // not request it just for a label, so "null" (shown as "Wi-Fi") is normal.
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps.transportInfo as? WifiInfo
        } else {
            runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        }
        val s = info?.ssid?.removeSurrounding("\"") ?: return null
        return s.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.removeSurrounding("\"") && it != "<unknown ssid>" }
    }

    private companion object { const val TAG = "NetworkMonitor" }
}
