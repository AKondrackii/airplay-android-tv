package com.airplaytv.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.airplaytv.app.AirPlayApp
import com.airplaytv.app.MainActivity
import com.airplaytv.app.R
import com.airplaytv.core.AppLog
import com.airplaytv.core.ContentType
import com.airplaytv.core.IncomingBehavior
import com.airplaytv.core.ReceiverStatus
import com.airplaytv.core.UserPrompt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the receiver for the whole time it should be reachable,
 * independent of the UI. While idle it only holds a listening socket and the mDNS
 * responder (no wake lock, no Wi-Fi lock); during a session it keeps the CPU and Wi-Fi
 * awake so long streams do not stutter.
 */
class ReceiverService : LifecycleService() {
    private val graph by lazy { (application as AirPlayApp).graph }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var streamingForeground = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground(buildNotification(ReceiverStatus.Starting), streaming = false)
        graph.controller.start()

        lifecycleScope.launch {
            graph.controller.status.collect { st ->
                val streaming = st is ReceiverStatus.Connected
                goForeground(buildNotification(st), streaming)
                setStreamingLocks(streaming)
            }
        }
        // Bring the player forward when a session with picture or sound starts.
        lifecycleScope.launch {
            combine(graph.controller.status, graph.settings.settings) { st, s ->
                (st as? ReceiverStatus.Connected)?.session?.content to s.incomingBehavior
            }.distinctUntilChanged().collect { (content, behavior) ->
                if (content != null && content != ContentType.NONE && behavior == IncomingBehavior.AUTO_OPEN) {
                    bringToFront(MainActivity.EXTRA_SHOW_PLAYER)
                }
            }
        }
        // PIN and confirmation must be visible on the TV even if another app is open.
        lifecycleScope.launch {
            graph.policy.prompt.map { it?.javaClass to (it as? UserPrompt.ConfirmClient)?.id }
                .distinctUntilChanged()
                .collect { (cls, _) ->
                    val p = graph.policy.prompt.value ?: return@collect
                    bringToFront(null)
                    if (!AppVisibility.foreground) postPromptNotification(p)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.controller.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        setStreamingLocks(false)
        graph.controller.stop()
        super.onDestroy()
    }

    private fun bringToFront(extra: String?) {
        if (AppVisibility.foreground) {
            if (extra != null) AppVisibility.requestPlayer()
            return
        }
        // Android 10+ only lets an app start an activity from the background if it holds
        // "display over other apps"; otherwise we fall back to the notification.
        val allowed = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)
        if (!allowed) return
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (extra != null) i.putExtra(extra, true)
        runCatching { startActivity(i) }.onFailure { AppLog.w(TAG, "cannot open player from background", it) }
    }

    private fun goForeground(n: Notification, streaming: Boolean) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (streaming) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            try {
                startForeground(NOTIF_ID, n, type)
            } catch (t: Throwable) {
                // e.g. mediaPlayback type refused in some background states: keep connectedDevice
                AppLog.w(TAG, "foreground type update refused", t)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
        streamingForeground = streaming
    }

    private fun setStreamingLocks(on: Boolean) {
        if (on) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPlayTV:session")
                    .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = getSystemService(WifiManager::class.java)?.createWifiLock(mode, "AirPlayTV:session")
                    ?.apply { setReferenceCounted(false); acquire() }
            }
        } else {
            wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            wakeLock = null
            wifiLock?.let { runCatching { if (it.isHeld) it.release() } }
            wifiLock = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_desc); setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(st: ReceiverStatus): Notification {
        val name = graph.controller.network.value?.let { "${it.primaryIpv4}" } ?: ""
        val text = when (st) {
            is ReceiverStatus.Connected -> getString(R.string.notif_connected, st.session.client.displayName)
            is ReceiverStatus.Ready -> getString(R.string.notif_ready, name)
            ReceiverStatus.NoNetwork -> getString(R.string.notif_no_network)
            is ReceiverStatus.Error -> st.message
            else -> getString(R.string.app_name)
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun postPromptNotification(p: UserPrompt) {
        val text = when (p) {
            is UserPrompt.ShowPin -> getString(R.string.notif_pin, p.pin)
            is UserPrompt.ConfirmClient -> getString(R.string.notif_confirm, p.client.displayName)
        }
        val n = NotificationCompat.Builder(this, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(contentIntent())
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_PROMPT_ID, n) }
    }

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL = "receiver"
        private const val CHANNEL_PROMPT = "receiver_prompt"
        private const val NOTIF_ID = 1
        private const val NOTIF_PROMPT_ID = 2
        const val ACTION_STOP = "com.airplaytv.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }

        fun stop(context: Context) {
            // startService() may be refused once the app is in the background; stopService
            // then still tears the service down (onDestroy stops the receiver).
            runCatching {
                context.startService(Intent(context, ReceiverService::class.java).setAction(ACTION_STOP))
            }.onFailure { context.stopService(Intent(context, ReceiverService::class.java)) }
        }
    }
}
