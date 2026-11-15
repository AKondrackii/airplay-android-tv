package com.airplaytv.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How senders must authenticate. Every mode is enforced inside the AirPlay protocol
 * handshake by the native receiver, not just displayed in the UI.
 */
enum class AuthMode {
    /** Anyone on the allowed local network may connect (subject to confirmation, if enabled). */
    NONE,
    /** Legacy AirPlay PIN pairing (SRP-6a). A 4-digit PIN is shown on the TV the first time;
     *  the sender's Ed25519 key is then remembered as a trusted device. */
    PIN_PAIRING,
    /** Fixed password (HTTP Digest over RTSP). The sender asks for it; iOS may remember it. */
    PASSWORD,
    /** A new random 4-digit code is shown on the TV for every connection (HTTP Digest). */
    ONE_TIME_CODE;

    /** Value understood by UxPlay's pin_pw parameter. */
    val nativeValue: Int get() = ordinal
}

enum class ResolutionMode(val width: Int, val height: Int) {
    AUTO(0, 0), HD_720(1280, 720), FHD_1080(1920, 1080), UHD_2160(3840, 2160)
}

enum class IncomingBehavior {
    /** Bring the player to the front automatically (needs "display over other apps"). */
    AUTO_OPEN,
    /** Keep audio playing in the background and only post a notification. */
    NOTIFY_ONLY,
}

data class ReceiverSettings(
    val deviceName: String = DEFAULT_NAME,
    val autoStartOnBoot: Boolean = true,
    val authMode: AuthMode = AuthMode.NONE,
    val confirmNewDevices: Boolean = false,
    val resolution: ResolutionMode = ResolutionMode.AUTO,
    val maxFps: Int = 60,
    val hardwareDecoding: Boolean = true,
    val allowH265: Boolean = true,
    val hlsVideo: Boolean = true,
    val followSenderVolume: Boolean = true,
    val incomingBehavior: IncomingBehavior = IncomingBehavior.AUTO_OPEN,
    /** Keep the receiver running after leaving the app. Off = it stops with the app. */
    val runInBackground: Boolean = true,
) {
    companion object {
        const val DEFAULT_NAME = "Android TV"
        const val MAX_NAME_LENGTH = 40

        /** Bonjour instance names: printable, no dots (UxPlay rejects ".local"), bounded length. */
        fun sanitizeName(raw: String): String {
            val cleaned = raw.filter { !it.isISOControl() && it != '.' && it != '@' }.trim()
            return cleaned.take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME }
        }
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "receiver_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val name = stringPreferencesKey("device_name")
        val boot = booleanPreferencesKey("auto_start_boot")
        val auth = stringPreferencesKey("auth_mode")
        val confirm = booleanPreferencesKey("confirm_new_devices")
        val resolution = stringPreferencesKey("resolution")
        val fps = intPreferencesKey("max_fps")
        val hw = booleanPreferencesKey("hardware_decoding")
        val h265 = booleanPreferencesKey("allow_h265")
        val hls = booleanPreferencesKey("hls_video")
        val volume = booleanPreferencesKey("follow_sender_volume")
        val incoming = stringPreferencesKey("incoming_behavior")
        val background = booleanPreferencesKey("run_in_background")
    }

    val settings: Flow<ReceiverSettings> = context.dataStore.data.map { p ->
        val d = ReceiverSettings()
        ReceiverSettings(
            deviceName = p[Keys.name] ?: d.deviceName,
            autoStartOnBoot = p[Keys.boot] ?: d.autoStartOnBoot,
            authMode = enumOr(p[Keys.auth], d.authMode),
            confirmNewDevices = p[Keys.confirm] ?: d.confirmNewDevices,
            resolution = enumOr(p[Keys.resolution], d.resolution),
            maxFps = (p[Keys.fps] ?: d.maxFps).coerceIn(24, 60),
            hardwareDecoding = p[Keys.hw] ?: d.hardwareDecoding,
            allowH265 = p[Keys.h265] ?: d.allowH265,
            hlsVideo = p[Keys.hls] ?: d.hlsVideo,
            followSenderVolume = p[Keys.volume] ?: d.followSenderVolume,
            incomingBehavior = enumOr(p[Keys.incoming], d.incomingBehavior),
            runInBackground = p[Keys.background] ?: d.runInBackground,
        )
    }.distinctUntilChanged()

    suspend fun current(): ReceiverSettings = settings.first()

    suspend fun update(transform: (ReceiverSettings) -> ReceiverSettings) {
        context.dataStore.edit { p ->
            val d = ReceiverSettings()
            val cur = ReceiverSettings(
                deviceName = p[Keys.name] ?: d.deviceName,
                autoStartOnBoot = p[Keys.boot] ?: d.autoStartOnBoot,
                authMode = enumOr(p[Keys.auth], d.authMode),
                confirmNewDevices = p[Keys.confirm] ?: d.confirmNewDevices,
                resolution = enumOr(p[Keys.resolution], d.resolution),
                maxFps = p[Keys.fps] ?: d.maxFps,
                hardwareDecoding = p[Keys.hw] ?: d.hardwareDecoding,
                allowH265 = p[Keys.h265] ?: d.allowH265,
                hlsVideo = p[Keys.hls] ?: d.hlsVideo,
                followSenderVolume = p[Keys.volume] ?: d.followSenderVolume,
                incomingBehavior = enumOr(p[Keys.incoming], d.incomingBehavior),
                runInBackground = p[Keys.background] ?: d.runInBackground,
            )
            val n = transform(cur)
            p[Keys.name] = ReceiverSettings.sanitizeName(n.deviceName)
            p[Keys.boot] = n.autoStartOnBoot
            p[Keys.auth] = n.authMode.name
            p[Keys.confirm] = n.confirmNewDevices
            p[Keys.resolution] = n.resolution.name
            p[Keys.fps] = n.maxFps.coerceIn(24, 60)
            p[Keys.hw] = n.hardwareDecoding
            p[Keys.h265] = n.allowH265
            p[Keys.hls] = n.hlsVideo
            p[Keys.volume] = n.followSenderVolume
            p[Keys.incoming] = n.incomingBehavior.name
            p[Keys.background] = n.runInBackground
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}
