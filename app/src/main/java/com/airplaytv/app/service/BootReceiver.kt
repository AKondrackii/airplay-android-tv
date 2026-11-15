package com.airplaytv.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.airplaytv.app.AirPlayApp
import com.airplaytv.core.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts the receiver after the TV boots (or after an app update) when the user enabled
 * "Start with the TV". The service starts with the connectedDevice foreground type,
 * which Android 15 still permits from BOOT_COMPLETED (mediaPlayback is not).
 * Some manufacturer firmwares block boot broadcasts for apps the user never opened or
 * put into an "auto-start" deny list; that cannot be bypassed and is documented.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val graph = (context.applicationContext as AirPlayApp).graph
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (graph.settings.current().autoStartOnBoot) {
                    ReceiverService.start(context)
                    AppLog.i("BootReceiver", "receiver started at boot")
                }
            } catch (t: Throwable) {
                AppLog.e("BootReceiver", "auto-start failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
