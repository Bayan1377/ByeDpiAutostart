package ru.dsh.byediautostart

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Управление VPN «Incy» через его экспортированный VpnIntentReceiver.
 * Действия: llc.itdev.incy.CONNECT / llc.itdev.incy.DISCONNECT.
 */
object IncyClient {

    const val PACKAGE = "llc.itdev.incy"
    private const val RECEIVER = "$PACKAGE.receiver.VpnIntentReceiver"
    private const val TAG = "IncyClient"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun start(context: Context): Boolean = send(context, "CONNECT")

    fun stop(context: Context): Boolean = send(context, "DISCONNECT")

    private fun send(context: Context, action: String): Boolean {
        val intent = Intent("$PACKAGE.$action")
            .setComponent(ComponentName(PACKAGE, RECEIVER))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return try {
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            AutomationState.lastError = e.message
            Log.e(TAG, "Не удалось отправить $action", e)
            false
        }
    }
}
