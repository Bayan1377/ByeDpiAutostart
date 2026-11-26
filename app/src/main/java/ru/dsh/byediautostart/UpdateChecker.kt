package ru.dsh.byediautostart

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Раз в сутки проверяет GitHub-релизы ByeByeDPI и сразу скачивает APK.
 * Установку подтверждает пользователь: Android не разрешает ставить APK молча.
 */
@SuppressLint("StaticFieldLeak")
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val API_URL = "https://api.github.com/repos/romanvht/ByeByeDPI/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    enum class Status { IDLE, CHECKING, DOWNLOADING, READY, NEED_PERMISSION, ERROR }

    data class UiState(
        val status: Status = Status.IDLE,
        val version: String = "",
        val progress: Int = 0,
        val message: String = "",
    )

    @Volatile
    var state: UiState = UiState()
        private set

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var app: Context? = null
    private var prefs: Prefs? = null

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        prefs = Prefs(app!!)
        restoreReady()
    }

    fun maybeCheck(context: Context) {
        init(context)
        val p = prefs ?: return
        if (state.status != Status.IDLE) return
        if (System.currentTimeMillis() - p.lastUpdateCheck < DAY_MS) return
        if (ByeDpiClient.findPackage(app!!) == null) return
        check(manual = false)
    }

    fun checkNow(context: Context) {
        init(context)
        check(manual = true)
    }

    fun retry(context: Context) {
        init(context)
        if (state.status == Status.ERROR) check(manual = true)
    }

    /** После установки APK убирает устаревшую карточку и скачанный файл. */
    fun refreshAfterInstall(context: Context) {
        init(context)
        val p = prefs ?: return
        val version = p.downloadedVersion ?: return
        val pkg = ByeDpiClient.findPackage(context) ?: return
        if (!VersionComparator.isNewer(version, installedVersion(context, pkg))) {
            p.downloadedPath?.let { File(it).delete() }
            p.downloadedVersion = null
            p.downloadedPath = null
            if (state.status == Status.READY || state.status == Status.NEED_PERMISSION) {
                state = UiState(Status.IDLE)
            }
        }
    }

    fun install(context: Context) {
        init(context)
        val appContext = app ?: return
        val p = prefs ?: return
        val path = p.downloadedPath ?: run {
            state = UiState(Status.ERROR, message = "Файл обновления не найден")
            return
        }
        val file = File(path)
        if (!file.exists()) {
            state = UiState(Status.ERROR, message = "Файл обновления удалён")
            return
        }

        if (!appContext.packageManager.canRequestPackageInstalls()) {
            state = UiState(Status.NEED_PERMISSION, version = p.downloadedVersion.orEmpty())
            openInstallPermission(appContext)
            return
        }

        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (e: Exception) {
            state = UiState(Status.ERROR, message = "Не удалось открыть установщик")
            Log.e(TAG, "install", e)
        }
    }

    private fun check(manual: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val appContext = app ?: return
        val p = prefs ?: return
        state = UiState(Status.CHECKING)

        executor.execute {
            try {
                val pkg = ByeDpiClient.findPackage(appContext)
                if (pkg == null) {
                    state = UiState(Status.IDLE)
                    return@execute
                }
                val release = fetchLatest()
                p.lastUpdateCheck = System.currentTimeMillis()
                p.latestVersion = release.version

                val installed = installedVersion(appContext, pkg)
                if (!VersionComparator.isNewer(release.version, installed)) {
                    state = UiState(Status.IDLE)
                    return@execute
                }

                val file = fileFor(release.version)
                if (file.exists() && validate(file, pkg)) {
                    markReady(file, release.version)
                    return@execute
                }
                download(release, file, pkg)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                state = if (manual) {
                    UiState(Status.ERROR, message = e.message ?: "Нет связи с GitHub")
                } else {
                    // Ежедневная фоновая проверка не должна пугать карточкой из-за плохой сети.
                    UiState(Status.IDLE)
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun fetchLatest(): Release {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ByeDpiAutostart/1.1")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ответил ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = VersionComparator.normalize(json.getString("tag_name"))
            if (version.isBlank()) throw IOException("Не удалось разобрать версию")

            val assets = json.getJSONArray("assets")
            var url: String? = null
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
            val chosen = apks.firstOrNull { it.getString("name").contains("universal", ignoreCase = true) }
                ?: apks.firstOrNull { it.getString("name").contains("arm64-v8a", ignoreCase = true) }
                ?: apks.firstOrNull()
            if (chosen != null) {
                url = chosen.getString("browser_download_url")
            }
            if (url.isNullOrBlank()) throw IOException("В релизе нет APK")
            return Release(version, url)
        } finally {
            connection.disconnect()
        }
    }

    private fun download(release: Release, target: File, pkg: String) {
        state = UiState(Status.DOWNLOADING, version = release.version, progress = 0)
        val dir = target.parentFile ?: throw IOException("Нет папки для загрузки")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку")
        val temp = File(target.absolutePath + ".part")
        if (temp.exists()) temp.delete()

        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ByeDpiAutostart/1.1")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Скачивание: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPercent = -1
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                state = UiState(Status.DOWNLOADING, release.version, percent)
                            }
                        }
                    }
                }
            }
            if (temp.length() < 100_000L) throw IOException("Файл слишком мал")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("Не удалось сохранить APK")
            if (!validate(target, pkg)) {
                target.delete()
                throw IOException("Подпись APK не совпадает с установленным ByeByeDPI")
            }
            markReady(target, release.version)
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun markReady(file: File, version: String) {
        val p = prefs ?: return
        p.downloadedVersion = version
        p.downloadedPath = file.absolutePath
        state = UiState(Status.READY, version)
    }

    private fun restoreReady() {
        val p = prefs ?: return
        val path = p.downloadedPath ?: return
        val version = p.downloadedVersion ?: return
        val context = app ?: return
        val pkg = ByeDpiClient.findPackage(context)
        val stillNeeded = pkg != null && VersionComparator.isNewer(version, installedVersion(context, pkg))
        if (stillNeeded && File(path).exists()) {
            state = UiState(Status.READY, version)
        } else {
            p.downloadedPath = null
            p.downloadedVersion = null
        }
    }

    private fun fileFor(version: String): File {
        val safe = version.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        val dir = File(app!!.getExternalFilesDir(null), "updates")
        return File(dir, "byedpi-$safe.apk")
    }

    private fun installedVersion(context: Context, pkg: String): String = try {
        context.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, pkg: String): Boolean {
        val context = app ?: return false
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: return false
        if (archive.packageName != pkg) return false
        val installed = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val downloaded = archive.signatures?.firstOrNull()?.toByteArray() ?: return false
        val current = installed.signatures?.firstOrNull()?.toByteArray() ?: return false
        return downloaded.contentEquals(current)
    }

    private fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Нет экрана разрешения установки", e)
        }
    }

    private data class Release(val version: String, val url: String)
}
