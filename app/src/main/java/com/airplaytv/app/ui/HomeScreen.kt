package com.airplaytv.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airplaytv.app.update.UpdateManager
import com.airplaytv.core.ContentType
import com.airplaytv.core.LocalNetwork
import com.airplaytv.core.ReceiverStatus
import com.airplaytv.core.Transport

@Composable
fun HomeScreen(
    status: ReceiverStatus,
    network: LocalNetwork?,
    deviceName: String,
    serviceRunning: Boolean,
    onSettings: () -> Unit,
    onOpenPlayer: () -> Unit,
    onDisconnect: () -> Unit,
    onStartService: () -> Unit,
    update: UpdateManager.State = UpdateManager.State.Idle,
    onUpdate: () -> Unit = {},
) {
    val primaryFocus = remember { FocusRequester() }
    AuroraBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            // Top bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GText("AIRPLAY RECEIVER", GlassType.Caption)
                Box(Modifier.weight(1f))
                updateLabel(update)?.let { label ->
                    GlassButton(onClick = onUpdate, contentPadding = 16.dp, tint = GlassTokens.Positive.copy(alpha = 0.16f)) {
                        GText(label, GlassType.Label)
                    }
                    Spacer(width = 14.dp)
                }
                GlassButton(onClick = onSettings, contentPadding = 16.dp) { GText("Настройки", GlassType.Label) }
            }

            // Center stage
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ReceiverOrb(active = status is ReceiverStatus.Connected, modifier = Modifier.size(168.dp))
                Spacer(height = 18.dp)
                AnimatedContent(
                    targetState = status,
                    contentKey = { it::class },
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                    label = "status",
                ) { st ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (st) {
                            is ReceiverStatus.Connected -> ConnectedCard(st, onOpenPlayer, onDisconnect, primaryFocus)
                            else -> IdleBlock(st, deviceName, serviceRunning, onStartService)
                        }
                    }
                }
            }

            BottomBar(network, deviceName, Modifier.align(Alignment.CenterHorizontally))
        }
    }
    LaunchedEffect(status is ReceiverStatus.Connected) {
        if (status is ReceiverStatus.Connected) runCatching { primaryFocus.requestFocus() }
    }
}

@Composable
private fun IdleBlock(st: ReceiverStatus, deviceName: String, serviceRunning: Boolean, onStart: () -> Unit) {
    val (title, hint) = when (st) {
        is ReceiverStatus.Ready -> "Готово к подключению" to
            "Повтор экрана → выберите «$deviceName»"
        ReceiverStatus.Starting -> "Запуск приёмника…" to "Подготавливаем сетевой приёмник"
        ReceiverStatus.NoNetwork -> "Нет локальной сети" to
            "Подключите телевизор к Wi-Fi или Ethernet. Мобильная сеть и VPN не используются."
        is ReceiverStatus.Error -> "Приёмник не запущен" to st.message
        ReceiverStatus.Stopped -> "Приёмник выключен" to "Фоновый сервис остановлен"
        is ReceiverStatus.Connected -> "" to ""
    }
    GText(title, GlassType.Display, align = TextAlign.Center)
    Spacer(height = 10.dp)
    GText(hint, GlassType.Body, modifier = Modifier.widthIn(max = 620.dp), align = TextAlign.Center)
    if (!serviceRunning) {
        Spacer(height = 18.dp)
        GlassLabelButton("Включить приёмник", onStart, tint = GlassTokens.Accent.copy(alpha = 0.18f))
    }
}

@Composable
private fun ConnectedCard(st: ReceiverStatus.Connected, onOpen: () -> Unit, onDisconnect: () -> Unit, focus: FocusRequester) {
    val what = when (st.session.content) {
        ContentType.MIRRORING -> "Повтор экрана"
        ContentType.VIDEO -> "Видео"
        ContentType.AUDIO -> "Музыка и звук"
        ContentType.NONE -> "Устанавливается соединение…"
    }
    GText("Подключено", GlassType.Caption, color = GlassTokens.Positive)
    Spacer(height = 6.dp)
    GText(st.session.client.displayName, GlassType.Display, align = TextAlign.Center, maxLines = 1)
    Spacer(height = 6.dp)
    GText(what, GlassType.Body)
    Spacer(height = 20.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassButton(onClick = onOpen, modifier = Modifier.focusRequester(focus), tint = GlassTokens.Accent.copy(alpha = 0.18f)) {
            GText("Открыть экран", GlassType.Label)
        }
        GlassButton(onClick = onDisconnect, tint = GlassTokens.Negative.copy(alpha = 0.14f)) {
            GText("Отключить", GlassType.Label)
        }
    }
}

@Composable
private fun BottomBar(network: LocalNetwork?, deviceName: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(0.78f).glass(RoundedCornerShape(GlassTokens.RadiusM)).padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoItem("IP-адрес", network?.primaryIpv4 ?: "—", Modifier.weight(1f))
        InfoItem("Имя устройства", deviceName, Modifier.weight(1f), Alignment.CenterHorizontally)
        InfoItem(
            "Сеть",
            when {
                network == null -> "Не подключено"
                network.transport == Transport.ETHERNET -> "Ethernet"
                else -> network.ssid ?: "Wi-Fi"
            },
            Modifier.weight(1f),
            Alignment.End,
        )
    }
}

@Composable
private fun InfoItem(label: String, value: String, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        GText(label, GlassType.Caption)
        GText(value, GlassType.Mono, maxLines = 1)
    }
}

/** Top-bar update button text, or null when there is nothing to offer. */
fun updateLabel(state: UpdateManager.State): String? = when (state) {
    is UpdateManager.State.Available -> "Обновить до ${state.release.version}"
    is UpdateManager.State.Downloading -> "Загрузка обновления ${(state.progress * 100).toInt()}%"
    is UpdateManager.State.Ready -> "Установить ${state.release.version}"
    is UpdateManager.State.Installing -> "Установка…"
    is UpdateManager.State.Failed -> state.release?.let { "Повторить обновление" }
    else -> null
}
