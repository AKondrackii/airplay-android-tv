package com.airplaytv.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.airplaytv.app.service.AppVisibility
import com.airplaytv.app.service.ReceiverService
import com.airplaytv.app.ui.ConfirmOverlay
import com.airplaytv.app.ui.HomeScreen
import com.airplaytv.app.ui.LogScreen
import com.airplaytv.app.ui.PinOverlay
import com.airplaytv.app.ui.PlayerScreen
import com.airplaytv.app.ui.SettingsScreen
import com.airplaytv.core.ReceiverSettings
import com.airplaytv.core.ReceiverStatus
import com.airplaytv.core.UserPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class Screen { HOME, SETTINGS, LOG, PLAYER }

/**
 * The only Activity. It is purely a view onto the receiver running in [ReceiverService];
 * closing it never stops a session (unless "Run in background" is switched off).
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as AirPlayApp).graph }
    private val showPlayer = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        maybeAskNotificationPermission()
        lifecycleScope.launch { AppVisibility.playerRequests.collect { showPlayer.value = true } }

        setContent {
            val status by graph.controller.status.collectAsState()
            val network by graph.controller.network.collectAsState()
            val settings by graph.settings.settings.collectAsState(initial = ReceiverSettings())
            val running by graph.controller.serviceRunning.collectAsState()
            val prompt by graph.policy.prompt.collectAsState()
            val wantPlayer by showPlayer.collectAsState()
            var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

            LaunchedEffect(wantPlayer) {
                if (wantPlayer) { screen = Screen.PLAYER; showPlayer.value = false }
            }
            // Leave the player automatically when the sender ends the session.
            LaunchedEffect(status is ReceiverStatus.Connected) {
                if (status !is ReceiverStatus.Connected && screen == Screen.PLAYER) screen = Screen.HOME
            }
            LaunchedEffect(screen) {
                if (screen == Screen.PLAYER) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            BackHandler(enabled = screen != Screen.HOME) {
                screen = if (screen == Screen.LOG) Screen.SETTINGS else Screen.HOME
            }

            Box(Modifier.fillMaxSize()) {
                AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { sc ->
                    when (sc) {
                        Screen.HOME -> HomeScreen(
                            status = status,
                            network = network,
                            deviceName = settings.deviceName,
                            serviceRunning = running,
                            onSettings = { screen = Screen.SETTINGS },
                            onOpenPlayer = { screen = Screen.PLAYER },
                            onDisconnect = { graph.controller.disconnectAll() },
                            onStartService = { ReceiverService.start(this@MainActivity) },
                        )
                        Screen.SETTINGS -> SettingsScreen(graph, onOpenLog = { screen = Screen.LOG })
                        Screen.LOG -> LogScreen()
                        Screen.PLAYER -> PlayerScreen(graph.playback, status)
                    }
                }
                Prompts(prompt)
            }
        }
    }

    @Composable
    private fun Prompts(prompt: UserPrompt?) {
        when (prompt) {
            is UserPrompt.ShowPin -> PinOverlay(prompt)
            is UserPrompt.ConfirmClient -> ConfirmOverlay(prompt) { allow, remember ->
                graph.policy.answerPrompt(prompt.id, allow, remember)
            }
            null -> Unit
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_SHOW_PLAYER, false) == true) showPlayer.value = true
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
        // Idempotent; also restarts the receiver if "Run in background" stopped it in onStop().
        ReceiverService.start(this)
    }

    override fun onStop() {
        AppVisibility.foreground = false
        super.onStop()
        lifecycleScope.launch {
            val s = graph.settings.current()
            if (!s.runInBackground && graph.controller.status.value !is ReceiverStatus.Connected && !isChangingConfigurations) {
                ReceiverService.stop(this@MainActivity)
            }
        }
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        }
    }

    companion object {
        const val EXTRA_SHOW_PLAYER = "show_player"
    }
}
