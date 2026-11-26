package ru.dsh.byediautostart

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Проверка обновлений самого ByeDpiAutostart по GitHub-релизам. */
object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val API_URL = "https://api.github.com/repos/Bayan1377/ByeDpiAutostart/releases/latest"
    private val checkedThisLaunch = AtomicBoolean(false)

    fun checkOnLaunch(activity: Activity) {
        if (!checkedThisLaunch.compareAndSet(false, true)) return
        check(activity, manual = false)
    }

    fun checkNow(activity: Activity) {
        check(activity, manual = true)
    }

    private fun check(activity: Activity, manual: Boolean) {
        val context = activity.applicationContext
        val current = installedVersion(context)
        Thread {
            try {
                val release = fetchLatest()
                val newer = VersionComparator.isNewer(release.version, current)
                if (!newer) {
                    if (manual) {
                        activity.runOnUiThread {
                            if (!activity.isFinishing) {
                                Toast.makeText(activity, R.string.app_update_latest, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    return@Thread
                }
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    showDialog(activity, release)
                }
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                if (manual) {
                    activity.runOnUiThread {
                        if (!activity.isFinishing) {
                            Toast.makeText(activity, R.string.app_update_error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.start()
    }

    private fun showDialog(activity: Activity, release: Release) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.app_update_title)
            .setMessage(activity.getString(R.string.app_update_text, release.version))
            .setPositiveButton(R.string.app_update_download) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)))
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.app_update_error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetchLatest(): Release {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "ByeDpiAutostart")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = VersionComparator.normalize(json.getString("tag_name"))
            if (version.isBlank()) throw IOException("Нет версии")
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").equals("ByeDpiAutostart.apk", ignoreCase = true)) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) throw IOException("В релизе нет APK")
            return Release(version, apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersion(context: android.content.Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    private data class Release(val version: String, val apkUrl: String)
}
