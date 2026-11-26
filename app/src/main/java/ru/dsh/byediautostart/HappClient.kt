package ru.dsh.byediautostart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.content.pm.PackageManager

/**
 * Happ поддерживает deep links для автоматизации:
 * happ://connect_without_ui и happ://disconnect_without_ui (с версии 3.25).
 */
object HappClient {

    private const val TAG = "HappClient"
    private val KNOWN_PACKAGES = listOf("su.happ.proxyutility", "com.happproxy")

    fun findPackage(context: Context): String? {
        for (pkg in KNOWN_PACKAGES) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: Exception) {
                // не установлен
            }
        }
        // Happ мог быть установлен под другим именем пакета — ищем по обработчику
        // deep link без открытия приложения.
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("happ://connect_without_ui"))
        val resolved = try {
            context.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            emptyList()
        }
        resolved.firstOrNull { it.activityInfo?.exported == true }
            ?.activityInfo?.packageName
            ?.let { return it }

        // Запасной путь: VPN-приложение с «Happ» в названии.
        try {
            for (info in context.packageManager.getInstalledPackages(PackageManager.MATCH_ALL)) {
                if (info.packageName == HappPlusClient.PACKAGE) continue
                val appInfo = info.applicationInfo ?: continue
                val label = context.packageManager.getApplicationLabel(appInfo).toString()
                if (!label.contains("happ", ignoreCase = true)) continue
                val hasVpn = info.services?.any { it.permission == "android.permission.BIND_VPN_SERVICE" } == true
                if (hasVpn) return info.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan happ failed", e)
        }
        return null
    }

    fun isInstalled(context: Context): Boolean = findPackage(context) != null

    fun start(context: Context): Boolean = send(context, "connect_without_ui")

    fun stop(context: Context): Boolean = send(context, "disconnect_without_ui")

    private fun send(context: Context, host: String): Boolean {
        val pkg = findPackage(context) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("happ://$host"))
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AutomationState.lastError = e.message
            Log.e(TAG, "Не удалось открыть happ://$host", e)
            false
        }
    }
}
