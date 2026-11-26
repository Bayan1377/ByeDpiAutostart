package ru.dsh.byediautostart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Проверяет именно системный VPN, а не «команду отправили»: так видно обрыв и реконнект. */
class VpnMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isVpnUp(): Boolean {
        val manager = cm ?: return false
        return try {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (e: Exception) {
            false
        }
    }
}
