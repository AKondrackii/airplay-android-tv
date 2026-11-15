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
) {
    val primaryFocus = remember { FocusRequester() }
    AuroraBackground {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            // Top bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GText("AIRPLAY RECEIVER", GlassType.Caption)
                Box(Modifier.weight(1f))
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

            BottomBar(network, status, serviceRunning)
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
            "Откройте Пункт управления на iPhone → Повтор экрана → выберите «$deviceName»"
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
    Spacer(height = 18.dp)
    if (!serviceRunning) {
        GlassLabelButton("Включить приёмник", onStart, tint = GlassTokens.Accent.copy(alpha = 0.18f))
    } else {
        Row(
            Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GText("Имя на iPhone  ", GlassType.Caption)
            GText(deviceName, GlassType.Headline, maxLines = 1)
        }
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
private fun BottomBar(network: LocalNetwork?, status: ReceiverStatus, serviceRunning: Boolean) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(GlassTokens.RadiusM)).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfoItem("IP-адрес", network?.primaryIpv4 ?: "—")
        InfoItem(
            "Сеть",
            when {
                network == null -> "Не подключено"
                network.transport == Transport.ETHERNET -> "Ethernet"
                else -> network.ssid ?: "Wi-Fi"
            },
        )
        val lanOk = network != null
        StatusPill(
            if (lanOk) "Только локальная сеть" else "Нет локальной сети",
            if (lanOk) GlassTokens.Positive else GlassTokens.Warning,
        )
        val (svcText, svcColor) = when {
            !serviceRunning -> "Сервис остановлен" to GlassTokens.Negative
            status is ReceiverStatus.Error -> "Ошибка сервиса" to GlassTokens.Negative
            else -> "Работает в фоне" to GlassTokens.Positive
        }
        StatusPill(svcText, svcColor, pulse = serviceRunning)
    }
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column {
        GText(label, GlassType.Caption)
        GText(value, GlassType.Mono, maxLines = 1)
    }
}
