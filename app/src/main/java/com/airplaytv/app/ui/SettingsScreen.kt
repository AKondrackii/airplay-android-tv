package com.airplaytv.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airplaytv.app.AppGraph
import com.airplaytv.app.BuildConfig
import com.airplaytv.app.service.ReceiverService
import com.airplaytv.app.update.UpdateManager
import com.airplaytv.core.AuthMode
import com.airplaytv.core.IncomingBehavior
import com.airplaytv.core.ReceiverSettings
import com.airplaytv.core.ResolutionMode
import com.airplaytv.playback.MirrorVideoDecoder
import kotlinx.coroutines.launch

private enum class Section(val title: String) {
    CONNECTION("Подключение"), SECURITY("Безопасность"), PLAYBACK("Воспроизведение"), SYSTEM("Система")
}

private sealed interface Editor {
    data object Name : Editor
    data object Password : Editor
}

@Composable
fun SettingsScreen(graph: AppGraph, onOpenLog: () -> Unit) {
    val s by graph.settings.settings.collectAsState(initial = ReceiverSettings())
    val running by graph.controller.serviceRunning.collectAsState()
    val trusted by graph.trustedDevices.devices.collectAsState()
    val updateState by graph.updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var section by rememberSaveable { mutableStateOf(Section.CONNECTION) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val hevcHw = remember { MirrorVideoDecoder.hasHardwareHevc() }

    fun update(f: (ReceiverSettings) -> ReceiverSettings) = scope.launch { graph.settings.update(f) }

    AuroraBackground(calm = true) {
        Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // Category rail
            Column(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GText("Настройки", GlassType.Title)
                Spacer(height = 14.dp)
                Section.entries.forEach { sec ->
                    GlassButton(
                        onClick = { section = sec },
                        modifier = Modifier.fillMaxWidth(),
                        tint = if (sec == section) GlassTokens.Accent.copy(alpha = 0.16f) else Color.Transparent,
                    ) { GText(sec.title, GlassType.Label) }
                }
            }

            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (section) {
                    Section.CONNECTION -> {
                        item { Header("Подключение") }
                        item { ValueRow("Имя устройства", s.deviceName, "Так телевизор называется в списке «Повтор экрана»") { editor = Editor.Name } }
                        item { ToggleRow("Запускать вместе с телевизором", s.autoStartOnBoot,
                            "Приёмник стартует после включения ТВ. Некоторые прошивки блокируют автозапуск.") { v -> update { it.copy(autoStartOnBoot = v) } } }
                        item {
                            ChoiceRow("Защита подключения", AuthMode.entries, s.authMode, ::authLabel, authHint(s.authMode)) { v ->
                                update { it.copy(authMode = v) }
                                if (v == AuthMode.PASSWORD && !graph.controller.hasPassword()) editor = Editor.Password
                            }
                        }
                        if (s.authMode == AuthMode.PASSWORD) {
                            item { ValueRow("Пароль", if (graph.controller.hasPassword()) "••••••" else "Не задан",
                                "Хранится зашифрованным (Android Keystore)") { editor = Editor.Password } }
                        }
                        item { ToggleRow("Подтверждать новые устройства", s.confirmNewDevices,
                            if (s.confirmNewDevices) "Неизвестное устройство подключится только после «Разрешить» на телевизоре"
                            else "Выключено: любое устройство из вашей локальной сети подключается без подтверждения") { v ->
                            update { it.copy(confirmNewDevices = v) } } }
                    }
                    Section.SECURITY -> {
                        item { Header("Безопасность") }
                        item { InfoRow("Только локальная сеть", "Всегда включено",
                            "Принимаются только устройства из подсети Wi-Fi/Ethernet телевизора. Мобильная сеть, VPN, другие подсети и гостевые сети отклоняются. Интернет-доступа и облака нет.") }
                        item { Header("Доверенные устройства") }
                        if (trusted.isEmpty()) item { InfoRow("Список пуст", "", "Устройства попадают сюда после PIN-сопряжения или «Разрешить и запомнить»") }
                        trusted.forEach { d ->
                            item(key = d.deviceId) {
                                val sure = confirmDelete == d.deviceId
                                ValueRow(
                                    d.name,
                                    if (sure) "Нажмите ещё раз, чтобы удалить" else "Удалить",
                                    if (d.publicKey != null) "PIN-сопряжение · ключ проверяется при каждом подключении"
                                    else "Подтверждено вручную · опознаётся по ID устройства",
                                ) {
                                    if (sure) { graph.trustedDevices.remove(d.deviceId); confirmDelete = null }
                                    else confirmDelete = d.deviceId
                                }
                            }
                        }
                        if (trusted.isNotEmpty()) item { ActionRow("Удалить все доверенные устройства", GlassTokens.Negative) { graph.trustedDevices.clear() } }
                        item { ActionRow("Завершить все сессии", GlassTokens.Negative) { graph.controller.disconnectAll() } }
                    }
                    Section.PLAYBACK -> {
                        item { Header("Воспроизведение") }
                        item { ChoiceRow("Разрешение повтора экрана", ResolutionMode.entries, s.resolution, ::resLabel,
                            "Сообщается iPhone/Mac; «Авто» — по экрану ТВ и возможностям декодера") { v -> update { it.copy(resolution = v) } } }
                        item { ChoiceRow("Частота кадров", listOf(30, 60), s.maxFps, { "$it к/с" },
                            "30 к/с снижает нагрузку на недорогих приставках") { v -> update { it.copy(maxFps = v) } } }
                        item { ChoiceRow("Масштаб изображения", listOf(false, true), s.videoFill,
                            { if (it) "Заполнить экран" else "Вписать (с рамками)" },
                            "«Вписать» сохраняет пропорции с чёрными рамками, «Заполнить» обрезает края. Во время показа — кнопка OK на пульте") { v ->
                            update { it.copy(videoFill = v) } } }
                        item { ToggleRow("Аппаратное декодирование", s.hardwareDecoding,
                            "Рекомендуется. Выключайте только при артефактах изображения") { v -> update { it.copy(hardwareDecoding = v) } } }
                        item { ToggleRow("H.265 (HEVC) для повтора экрана", s.allowH265 && hevcHw,
                            if (hevcHw) "Меньше трафика при том же качестве" else "Аппаратный декодер HEVC на этом устройстве не найден",
                            enabled = hevcHw) { v -> update { it.copy(allowH265 = v) } } }
                        item { ToggleRow("Видео по ссылке (AirPlay-видео)", s.hlsVideo,
                            "Кнопка AirPlay в YouTube, Safari и др. Видео с DRM (Netflix, Apple TV+) не поддерживается") { v -> update { it.copy(hlsVideo = v) } } }
                        item { ToggleRow("Громкость с iPhone", s.followSenderVolume,
                            "Кнопки громкости iPhone меняют громкость на ТВ") { v -> update { it.copy(followSenderVolume = v) } } }
                        item { ChoiceRow("При входящем подключении", IncomingBehavior.entries, s.incomingBehavior,
                            { if (it == IncomingBehavior.AUTO_OPEN) "Открывать на весь экран" else "Только уведомление" },
                            overlayHint(ctx)) { v -> update { it.copy(incomingBehavior = v) } } }
                        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) {
                            item { ActionRow("Разрешить показ поверх других приложений", GlassTokens.Accent) { openOverlaySettings(ctx) } }
                        }
                    }
                    Section.SYSTEM -> {
                        item { Header("Система") }
                        item { ToggleRow("Работа в фоне", s.runInBackground,
                            "Приём продолжается, когда открыто другое приложение или главный экран") { v -> update { it.copy(runInBackground = v) } } }
                        item {
                            ValueRow("Фоновый сервис", if (running) "Работает · остановить" else "Остановлен · запустить",
                                "Foreground Service с уведомлением") {
                                if (running) ReceiverService.stop(ctx) else ReceiverService.start(ctx)
                            }
                        }
                        item { ToggleRow("Проверять обновления", s.checkUpdates,
                            "Раз в 12 часов — запрос к GitHub Releases проекта. Данные о вас и о подключениях не передаются") { v ->
                            update { it.copy(checkUpdates = v) } } }
                        item { ToggleRow("Загружать обновления автоматически", s.autoDownloadUpdates,
                            "Найденная версия скачивается и проверяется в фоне. Установку подтверждаете вы — так требует Android",
                            enabled = s.checkUpdates) { v -> update { it.copy(autoDownloadUpdates = v) } } }
                        item {
                            val (value, hint) = updateRowText(updateState)
                            ValueRow("Обновление", value, hint) {
                                when (updateState) {
                                    is UpdateManager.State.Available, is UpdateManager.State.Ready -> graph.updates.downloadAndInstall()
                                    is UpdateManager.State.Failed -> if ((updateState as UpdateManager.State.Failed).release != null)
                                        graph.updates.downloadAndInstall() else graph.updates.checkNow()
                                    is UpdateManager.State.Downloading, is UpdateManager.State.Installing, UpdateManager.State.Checking -> Unit
                                    else -> graph.updates.checkNow()
                                }
                            }
                        }
                        item { ActionRow("Журнал ошибок", GlassTokens.Accent, onOpenLog) }
                        item { InfoRow("Версия", BuildConfig.VERSION_NAME,
                            "Протокол: UxPlay (AirPlay mirroring, AirPlay-аудио, HLS-видео). Лицензия GPL-3.0. " +
                                "Компоненты: UxPlay, libplist (LGPL-2.1), Apple ALAC (Apache-2.0), OpenSSL 3.5 (Apache-2.0), JmDNS (Apache-2.0), AndroidX Media3 (Apache-2.0).") }
                        item { InfoRow("Приватность", "", "Нет аналитики и телеметрии. Видео и звук не сохраняются, история не ведётся.") }
                    }
                }
                item { Spacer(height = 24.dp) }
            }
        }

        editor?.let { ed ->
            when (ed) {
                Editor.Name -> TextEditDialog("Имя устройства", s.deviceName, secret = false,
                    validate = { it.isNotBlank() && it.length <= ReceiverSettings.MAX_NAME_LENGTH },
                    onDismiss = { editor = null }) { v -> update { it.copy(deviceName = v) }; editor = null }
                Editor.Password -> TextEditDialog("Пароль для AirPlay (4–64 символа)", "", secret = true,
                    validate = { it.length in 4..64 },
                    onDismiss = { editor = null }) { v -> graph.controller.setPassword(v); editor = null }
            }
        }
    }
}

private fun authLabel(m: AuthMode) = when (m) {
    AuthMode.NONE -> "Без PIN"
    AuthMode.PIN_PAIRING -> "PIN на экране"
    AuthMode.PASSWORD -> "Постоянный пароль"
    AuthMode.ONE_TIME_CODE -> "Одноразовый код"
}

private fun authHint(m: AuthMode) = when (m) {
    AuthMode.NONE -> "Подключение без кода (работает фильтр локальной сети и подтверждение, если включено)"
    AuthMode.PIN_PAIRING -> "При первом подключении на ТВ появится 4-значный PIN. После сопряжения устройство запоминается по криптографическому ключу"
    AuthMode.PASSWORD -> "iPhone запросит пароль. Проверяется протоколом (HTTP Digest)"
    AuthMode.ONE_TIME_CODE -> "Для каждого подключения на ТВ показывается новый 4-значный код"
}

private fun resLabel(r: ResolutionMode) = when (r) {
    ResolutionMode.AUTO -> "Авто"
    ResolutionMode.HD_720 -> "720p"
    ResolutionMode.FHD_1080 -> "1080p"
    ResolutionMode.UHD_2160 -> "4K"
}

private fun overlayHint(ctx: Context): String =
    if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx))
        "Плеер откроется сам, даже если запущено другое приложение"
    else "Чтобы плеер открывался поверх других приложений, нужно разрешение «Поверх других приложений»"

private fun openOverlaySettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure {
        // Many Android TV builds hide this screen; README explains the adb alternative.
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

// ---------------------------------------------------------------------------------------
// Rows

@Composable
private fun Header(text: String) {
    GText(text.uppercase(), GlassType.Caption, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp))
}

@Composable
private fun RowShell(title: String, subtitle: String?, onClick: (() -> Unit)?, enabled: Boolean = true, trailing: @Composable () -> Unit) {
    val content: @Composable RowScope.(Boolean) -> Unit = { _ ->
        Column(Modifier.weight(1f)) {
            GText(title, GlassType.Headline, color = if (enabled) Color.Unspecified else GlassTokens.TextTertiary, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) GText(subtitle, GlassType.Caption, maxLines = 3)
        }
        trailing()
    }
    if (onClick != null && enabled) {
        GlassButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = 22.dp) { f -> content(f) }
    } else {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(GlassTokens.RadiusM)).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(false) }
    }
}

@Composable
private fun ToggleRow(title: String, value: Boolean, subtitle: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    RowShell(title, subtitle, { onChange(!value) }, enabled) { GlassSwitch(value && enabled) }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, value: T, label: (T) -> String, subtitle: String?, onChange: (T) -> Unit) {
    RowShell(title, subtitle, {
        val i = options.indexOf(value)
        onChange(options[(i + 1) % options.size])
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText(label(value), GlassType.Label, color = GlassTokens.Accent)
            GText("  ›", GlassType.Label, color = GlassTokens.TextTertiary)
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, subtitle: String?, onClick: () -> Unit) {
    RowShell(title, subtitle, onClick) { GText(value, GlassType.Label, color = GlassTokens.Accent, maxLines = 1) }
}

@Composable
private fun InfoRow(title: String, value: String, subtitle: String?) {
    RowShell(title, subtitle, null) { if (value.isNotEmpty()) GText(value, GlassType.Label, color = GlassTokens.Positive) }
}

@Composable
private fun ActionRow(title: String, color: Color, onClick: () -> Unit) {
    GlassButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), tint = color.copy(alpha = 0.12f), contentPadding = 22.dp) {
        GText(title, GlassType.Headline, color = color)
    }
}

@Composable
fun GlassSwitch(on: Boolean) {
    Box(
        Modifier.width(48.dp).height(28.dp).clip(RoundedCornerShape(50))
            .background(if (on) GlassTokens.Positive.copy(alpha = 0.85f) else Color(0x33FFFFFF)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).width(22.dp).height(22.dp).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

private fun updateRowText(st: UpdateManager.State): Pair<String, String> = when (st) {
    UpdateManager.State.Idle -> "Проверить" to "Установлена версия ${BuildConfig.VERSION_NAME}"
    UpdateManager.State.Checking -> "Проверка…" to "Запрос к GitHub Releases"
    UpdateManager.State.UpToDate -> "Последняя версия" to "Установлена версия ${BuildConfig.VERSION_NAME}. Нажмите, чтобы проверить снова"
    is UpdateManager.State.Available -> "Обновить до ${st.release.version}" to st.release.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    is UpdateManager.State.Downloading -> "Загрузка ${(st.progress * 100).toInt()}%" to "Файл проверяется по контрольной сумме и подписи"
    is UpdateManager.State.Ready -> "Установить ${st.release.version}" to "Загружено и проверено. Android попросит подтвердить установку"
    is UpdateManager.State.Installing -> "Установка…" to "Подтвердите установку в системном окне"
    is UpdateManager.State.Failed -> (if (st.release != null) "Повторить" else "Проверить") to st.message
}
