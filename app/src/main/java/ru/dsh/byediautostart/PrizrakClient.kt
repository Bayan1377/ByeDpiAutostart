package ru.dsh.byediautostart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Управление VPN «Призрак Box» через его ExternalControlActivity.
 * Класс активности ищется по action, поэтому переименование при обновлении не ломает
 * интеграцию, пока сохраняется intent-filter START/STOP.
 */
object PrizrakClient {

    const val PACKAGE = "ru.legiz.prizrakbox.meta"
    private const val ACTIVITY_FALLBACK = "ru.legiz.prizrakbox.ExternalControlActivity"
    private const val TAG = "PrizrakClient"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun start(context: Context): Boolean = send(context, "START")

    fun stop(context: Context): Boolean = send(context, "STOP")

    fun openApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun send(context: Context, action: String): Boolean {
        val intent = Intent("$PACKAGE.action.$action")
            .setComponent(resolveComponent(context, action))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AutomationState.lastError = e.message
            Log.e(TAG, "Не удалось отправить $action", e)
            false
        }
    }

    /**
     * Ищем экспортированную активность по action. Если приложение обновилось и
     * переименовало класс, findActivity всё равно вернёт новый ComponentName.
     */
    private fun resolveComponent(context: Context, action: String): ComponentName {
        val pm = context.packageManager
        val intent = Intent("$PACKAGE.action.$action").setPackage(PACKAGE)
        val info = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.exported }
            ?.activityInfo
        return if (info != null) {
            ComponentName(info.packageName, info.name)
        } else {
            ComponentName(PACKAGE, ACTIVITY_FALLBACK)
        }
    }
}
