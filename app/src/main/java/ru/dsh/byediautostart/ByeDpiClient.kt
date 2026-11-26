package ru.dsh.byediautostart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * Управление официальным ByeByeDPI через его собственную ToggleActivity.
 *
 * only_start / only_stop — штатные extras, которые используют плитка и ярлыки ByeByeDPI.
 * Интерфейс не открывается: активность объявлена NoDisplay и сразу завершается.
 */
object ByeDpiClient {

    private const val TAG = "ByeDpiClient"
    private const val TOGGLE_SUFFIX = ".activities.ToggleActivity"
    private val KNOWN_PACKAGES = listOf(
        "io.github.romanvht.byedpi",
        "io.github.romanvht.byedpi.debug",
        "com.byedpi.android",
        "byedpi.android",
    )

    fun findPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_PACKAGES) {
            if (hasToggleActivity(pm, pkg)) return pkg
        }
        for (info in pm.getInstalledPackages(0)) {
            val name = info.packageName.lowercase()
            if (("byedpi" in name || "byebyedpi" in name) && hasToggleActivity(pm, info.packageName)) {
                return info.packageName
            }
        }
        return null
    }

    fun start(context: Context, pkg: String): Boolean = send(context, pkg, start = true)

    fun stop(context: Context, pkg: String): Boolean = send(context, pkg, start = false)

    fun openApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось открыть $pkg", e)
            false
        }
    }

    /** После остановки VPN процесс ByeByeDPI остаётся в кэше; просим систему его убрать. */
    fun killProcess(context: Context, pkg: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "killBackgroundProcesses не сработал", e)
        }
    }

    fun openDownloadPage(context: Context) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://github.com/romanvht/ByeByeDPI/releases/latest"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Браузер недоступен", e)
        }
    }

    private fun send(context: Context, pkg: String, start: Boolean): Boolean {
        val intent = Intent()
            .setComponent(ComponentName(pkg, pkg + TOGGLE_SUFFIX))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(if (start) "only_start" else "only_stop", true)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AutomationState.lastError = e.message
            Log.e(TAG, "Команда ${if (start) "start" else "stop"} не отправлена", e)
            false
        }
    }

    private fun hasToggleActivity(pm: PackageManager, pkg: String): Boolean = try {
        pm.getActivityInfo(ComponentName(pkg, pkg + TOGGLE_SUFFIX), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
