package ru.dsh.byediautostart

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Предлагает установить ByeByeDPI на новом телефоне/планшете и скачивает APK с GitHub.
 * Молча установить нельзя: Android всегда показывает системный установщик.
 */
object ByeDpiInstaller {

    private const val TAG = "ByeDpiInstaller"
    private const val API_URL = "https://api.github.com/repos/romanvht/ByeByeDPI/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    fun maybeOffer(activity: Activity) {
        if (ByeDpiClient.findPackage(activity) != null) return
        val p = Prefs(activity)
        val now = System.currentTimeMillis()
        if (now - p.lastByeDpiPrompt < DAY_MS) return
        p.lastByeDpiPrompt = now
        offer(activity)
    }

    fun offer(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.byedpi_install_title)
            .setMessage(R.string.byedpi_install_text)
            .setPositiveButton(R.string.byedpi_install_download) { _, _ -> downloadAndInstall(activity) }
            .setNeutralButton(R.string.byedpi_install_site) { _, _ -> ByeDpiClient.openDownloadPage(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun downloadAndInstall(activity: Activity) {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(activity, R.string.byedpi_install_busy, Toast.LENGTH_SHORT).show()
            return
        }

        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.byedpi_install_downloading)
            .setMessage(activity.getString(R.string.byedpi_install_progress, 0))
            .setCancelable(false)
            .create()
        progress.show()

        val existing = activity.getExternalFilesDir("updates")?.let { File(it, "byedpi-install.apk") }
        if (existing != null && existing.exists() && isByeDpiApk(activity, existing)) {
            progress.dismiss()
            busy.set(false)
            install(activity, existing)
            return
        }

        executor.execute {
            try {
                val release = fetchLatest()
                val dir = activity.getExternalFilesDir("updates")
                    ?: throw IOException("Нет папки для загрузки")
                if (!dir.exists() && !dir.mkdirs()) throw IOException("Не удалось создать папку")
                val file = File(dir, "byedpi-install.apk")
                download(release.url, file, activity) { percent ->
                    activity.runOnUiThread {
                        progress.setMessage(activity.getString(R.string.byedpi_install_progress, percent))
                    }
                }
                activity.runOnUiThread {
                    progress.dismiss()
                    busy.set(false)
                    install(activity, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                activity.runOnUiThread {
                    progress.dismiss()
                    busy.set(false)
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.byedpi_install_failed, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun fetchLatest(): Release {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ByeDpiAutostart/1.7")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ответил ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(body).getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
            val chosen = apks.firstOrNull { it.getString("name").contains("universal", ignoreCase = true) }
                ?: apks.firstOrNull { it.getString("name").contains("arm64-v8a", ignoreCase = true) }
                ?: apks.firstOrNull()
                ?: throw IOException("В релизе нет APK")
            return Release(chosen.getString("browser_download_url"))
        } finally {
            connection.disconnect()
        }
    }

    private fun download(
        url: String,
        target: File,
        context: android.content.Context,
        onProgress: (Int) -> Unit,
    ) {
        val temp = File(target.absolutePath + ".part")
        if (temp.exists()) temp.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ByeDpiAutostart/1.7")
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
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (temp.length() < 100_000L) throw IOException("Файл слишком мал")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("Не удалось сохранить APK")
            val info = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
            if (info?.packageName?.contains("byedpi", ignoreCase = true) != true) {
                target.delete()
                throw IOException("Скачанный файл не похож на ByeByeDPI")
            }        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun isByeDpiApk(context: android.content.Context, file: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        return info.packageName?.contains("byedpi", ignoreCase = true) == true
    }

    private fun install(activity: Activity, file: File) {        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, R.string.byedpi_install_permission, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "no unknown sources screen", e)
            }
            return
        }

        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.byedpi_install_failed, Toast.LENGTH_LONG).show()
            Log.e(TAG, "install failed", e)
        }
    }

    private data class Release(val url: String)
}
