package ru.dsh.byediautostart

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Happ Plus (com.happ.plushap) не имеет внешнего API. Управляем его главным экраном
 * через службу доступности: центральная кнопка питания находится примерно на 32% высоты.
 */
object HappPlusClient {

    const val PACKAGE = "com.happ.plushap"
    private const val TAG = "HappPlusClient"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun start(context: Context): Boolean = toggle(context)

    fun stop(context: Context): Boolean = toggle(context)

    private fun toggle(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        val appContext = context.applicationContext
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                AppWatcherService.instance?.tapAtFraction(0.5f, 0.32f)
                handler.postDelayed({
                    AppWatcherService.instance?.performBack()
                }, 900L)
            }, 1_400L)
            true
        } catch (e: Exception) {
            AutomationState.lastError = e.message
            Log.e(TAG, "Не удалось открыть Happ Plus", e)
            false
        }
    }
}
