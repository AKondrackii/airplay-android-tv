package com.airplaytv.app

import android.content.Context
import com.airplaytv.airplay.AirPlayReceiver
import com.airplaytv.core.MediaSink
import com.airplaytv.core.SettingsRepository
import com.airplaytv.app.update.UpdateManager
import com.airplaytv.network.DacpClient
import com.airplaytv.network.MdnsAdvertiser
import com.airplaytv.network.NetworkMonitor
import com.airplaytv.playback.PlaybackEngine
import com.airplaytv.security.ReceiverPolicy
import com.airplaytv.security.SecretStore
import com.airplaytv.security.SubnetFilter
import com.airplaytv.security.TrustedDeviceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph (the app is small enough that a DI framework would only add
 * startup cost). Everything here is process-scoped and independent of any Activity.
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val secrets by lazy { SecretStore(appContext) }
    val trustedDevices by lazy { TrustedDeviceStore(secrets) }
    val subnets = SubnetFilter()
    val policy by lazy { ReceiverPolicy(subnets, trustedDevices) }
    val playback by lazy { PlaybackEngine(appContext, scope) }
    val network by lazy { NetworkMonitor(appContext, scope) }
    val mdns by lazy { MdnsAdvertiser(appContext) }
    val dacp by lazy { DacpClient(mdns, subnets::isLocalPeer) }
    val updates by lazy { UpdateManager(appContext, settings, scope) }

    /**
     * Loopback peers are only admitted while an AirPlay-video session runs, because the
     * local player then fetches the HLS playlist from the receiver's own proxy. At any
     * other time a local app connecting to the receiver port is refused.
     */
    private val sink: MediaSink by lazy {
        object : MediaSink by playback {
            override fun onHlsPlay(url: String, startPositionSec: Float) {
                subnets.allowLoopback = true
                playback.onHlsPlay(url, startPositionSec)
            }
            override fun onHlsStop() {
                subnets.allowLoopback = false
                playback.onHlsStop()
            }
        }
    }

    val receiver by lazy { AirPlayReceiver(policy, sink) }
    val controller by lazy { ReceiverController(this) }
}
