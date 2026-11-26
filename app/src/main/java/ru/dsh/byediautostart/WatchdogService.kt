package ru.dsh.byediautostart

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Живёт только пока нужен сценарий: включить VPN, следить за обрывом,
 * выключить после ухода. В простое не занимает процессор и не висит в шторке.
 */
class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotifiedVpn: Boolean? = null

    private val ticker = object : Runnable {
        override fun run() {
            AutomationController.onTick()
            updateNotification()
            if (AutomationState.watchdogRunning) {
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        AutomationState.watchdogRunning = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        AutomationState.watchdogRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = Notifications.running(this, AutomationState.vpnUp)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_RUN, notification, type)
        lastNotifiedVpn = AutomationState.vpnUp
    }

    private fun updateNotification() {
        if (lastNotifiedVpn == AutomationState.vpnUp) return
        lastNotifiedVpn = AutomationState.vpnUp
        val notification = Notifications.running(this, AutomationState.vpnUp)
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(Notifications.ID_RUN, notification)
    }

    companion object {
        private const val TICK_MS = 5_000L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                AutomationState.lastError = e.message
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }
    }
}
