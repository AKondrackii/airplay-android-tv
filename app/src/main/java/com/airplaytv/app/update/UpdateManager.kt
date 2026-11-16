package com.airplaytv.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.airplaytv.app.BuildConfig
import com.airplaytv.core.AppLog
import com.airplaytv.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the project's GitHub Releases.
 *
 * Check: `GET https://api.github.com/repos/<repo>/releases/latest` every 12 h (no data
 * about the user is sent). Download: the APK for this device's ABI. Verification before
 * offering the install: SHA-256 from the release's SHA256SUMS.txt, package name, higher
 * version code and the same signing certificate as the installed app. Install: through
 * PackageInstaller; Android always shows its own confirmation dialog.
 */
class UpdateManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    data class Release(
        val version: String,
        val versionCode: Int,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var loop: Job? = null
    private val dir get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Starts the periodic check (called by the background service). */
    fun start() {
        if (loop != null) return
        cleanupOldDownloads()
        loop = scope.launch(Dispatchers.IO) {
            delay(20_000)   // let the receiver start first
            while (true) {
                val s = settings.settings.first()
                if (s.checkUpdates) {
                    check(userInitiated = false)
                    if (s.autoDownloadUpdates) (state.value as? State.Available)?.let { download(it.release) }
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun checkNow() {
        scope.launch(Dispatchers.IO) { check(userInitiated = true) }
    }

    fun downloadAndInstall() {
        scope.launch(Dispatchers.IO) {
            when (val st = state.value) {
                is State.Available -> if (download(st.release)) install()
                is State.Failed -> st.release?.let { if (download(it)) install() }
                is State.Ready -> install()
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------------------------

    private suspend fun check(userInitiated: Boolean) = mutex.withLock {
        val cur = state.value
        if (cur is State.Downloading || cur is State.Ready || cur is State.Installing) return@withLock
        _state.value = State.Checking
        val release = try {
            fetchLatest()
        } catch (t: Throwable) {
            AppLog.w(TAG, "update check failed", t)
            _state.value = if (userInitiated) State.Failed("Не удалось проверить обновления") else State.Idle
            return@withLock
        }
        _state.value = when {
            release == null -> if (userInitiated) State.Failed("Релиз для этого устройства не найден") else State.Idle
            release.versionCode > BuildConfig.VERSION_CODE -> State.Available(release).also {
                AppLog.i(TAG, "update available: ${release.version}")
            }
            else -> State.UpToDate
        }
    }

    private fun fetchLatest(): Release? {
        val conn = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.getString("tag_name")
            val code = UpdateRules.versionCode(tag) ?: return null
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.getString("name") }
            val apkName = UpdateRules.pickApk(byName.keys.toList(), Build.SUPPORTED_ABIS.toList()) ?: return null
            val apk = byName.getValue(apkName)
            return Release(
                version = tag.removePrefix("v"),
                versionCode = code,
                notes = json.optString("body").take(2_000),
                apkName = apkName,
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                sumsUrl = byName["SHA256SUMS.txt"]?.getString("browser_download_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(release: Release): Boolean = mutex.withLock {
        (state.value as? State.Ready)?.let { if (it.release == release && it.file.exists()) return@withLock true }
        _state.value = State.Downloading(release, 0f)
        val target = File(dir, release.apkName)
        try {
            val sums = release.sumsUrl?.let { url -> open(url).let { c -> c.inputStream.use { it.readBytes() }.also { c.disconnect() } } }
                ?.toString(Charsets.UTF_8)
                ?: error("в релизе нет SHA256SUMS.txt")
            val expected = UpdateRules.sha256For(sums, release.apkName) ?: error("нет контрольной суммы для ${release.apkName}")

            val tmp = File(dir, "${release.apkName}.part")
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = open(release.apkUrl)
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                if (total > MAX_APK_BYTES) error("файл слишком большой")
                var done = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_APK_BYTES) error("файл слишком большой")
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            if (total > 0) _state.value = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                tmp.delete()
                error("контрольная сумма не совпала")
            }
            verifyPackage(tmp, release)
            target.delete()
            if (!tmp.renameTo(target)) error("не удалось сохранить файл")
            _state.value = State.Ready(release, target)
            AppLog.i(TAG, "update ${release.version} downloaded and verified")
            true
        } catch (t: Throwable) {
            AppLog.w(TAG, "update download failed", t)
            File(dir, "${release.apkName}.part").delete()
            _state.value = State.Failed("Не удалось загрузить обновление: ${t.message ?: "ошибка сети"}", release)
            false
        }
    }

    /** Same package, newer version, same signing certificate as the installed app. */
    private fun verifyPackage(apk: File, release: Release) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: error("файл не является приложением")
        if (archive.packageName != context.packageName) error("чужой пакет")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) error("версия не новее установленной")
        if (code != release.versionCode.toLong()) error("версия файла не совпадает с релизом")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveCerts = certs(archive)
        if (archiveCerts.isEmpty()) {
            // Some Android versions do not report certificates for an APK file; the system
            // installer still refuses an update signed with a different key.
            AppLog.i(TAG, "archive certificates unavailable; relying on the system signature check")
        } else if (archiveCerts != certs(installed)) {
            error("подпись не совпадает с установленным приложением")
        }
    }

    private fun certs(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Hands the verified APK to the system installer (which asks the user to confirm). */
    private suspend fun install() = withContext(Dispatchers.IO) {
        val ready = state.value as? State.Ready ?: return@withContext
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.Failed("Разрешите установку обновлений для AirPlay Receiver и нажмите «Обновить» ещё раз", ready.release)
            withContext(Dispatchers.Main) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            return@withContext
        }
        _state.value = State.Installing(ready.release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(ready.file.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "install session failed", t)
            _state.value = State.Failed("Не удалось начать установку", ready.release)
        }
    }

    /** Called by [InstallResultReceiver] when the installer reports a final result. */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = (state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate   // the process is usually restarted anyway
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                _state.value = release?.let { r -> File(dir, r.apkName).takeIf { it.exists() }?.let { State.Ready(r, it) } } ?: State.Idle
            else -> {
                AppLog.w(TAG, "install failed: $status $message")
                _state.value = State.Failed("Установка не удалась", release)
            }
        }
    }

    private fun cleanupOldDownloads() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "only https" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true   // release assets redirect to GitHub's CDN (https → https)
            setRequestProperty("User-Agent", "AirPlayReceiver-Android/${BuildConfig.VERSION_NAME}")
        }
    }

    private companion object {
        const val TAG = "Updates"
        const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
        const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
