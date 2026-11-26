package ru.dsh.byediautostart

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Следит, что «что-то не работает», и присылает важные уведомления:
 * нет интернета, VPN не поднялся, стратегия не пропускает YouTube, служба доступности выключена.
 *
 * Проверка стратегии — лёгкий GET к youtube.com/generate_204 именно через VPN-сеть.
 * Три неудачи подряд — уведомление; успех снимает его.
 */
object HealthMonitor {

    private const val TAG = "HealthMonitor"
    private const val PROBE_URL = "https://www.youtube.com/generate_204"
    private const val PROBE_INTERVAL_MS = 30_000L
    private const val CONNECTIVITY_INTERVAL_MS = 15_000L
    private const val ALERT_COOLDOWN_MS = 10 * 60_000L
    private const val FAILURES_TO_ALERT = 3

    private val executor = Executors.newSingleThreadExecutor()
    private val probing = AtomicBoolean(false)

    @Volatile private var internetUp = true
    private var lastConnectivityAt = 0L
    private var lastProbeAt = 0L
    private var strategyFailures = 0
    private var lastNoInternetAlertAt = 0L
    private var lastStrategyAlertAt = 0L
    private var lastAccessibilityAlertAt = 0L

    fun onTick(context: Context, targetActive: Boolean, vpnUp: Boolean) {
        val app = context.applicationContext

        val power = app.getSystemService(PowerManager::class.java)
        if (power?.isInteractive == false) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectivityAt >= CONNECTIVITY_INTERVAL_MS) {
            lastConnectivityAt = now
            updateInternet(app)
        }

        if (!targetActive) {
            resetStrategy(app)
            return
        }
        if (!internetUp) return

        if (!vpnUp) {
            resetStrategy(app)
            return
        }
        probeIfDue(app, now)
    }

    fun hasInternet(context: Context): Boolean = isInternetAvailable(context)

    /** Вызывается из startVpn, чтобы не долбить ByeByeDPI при выключенной сети. */
    fun reportNoInternet(context: Context) {
        internetUp = false
        alertNoInternet(context.applicationContext)
    }

    fun onTargetInactive(context: Context) {
        resetStrategy(context.applicationContext)
    }

    fun onPermissions(context: Context, accessibilityEnabled: Boolean, automationEnabled: Boolean) {
        val app = context.applicationContext
        if (accessibilityEnabled) {
            lastAccessibilityAlertAt = 0L
            Notifications.cancel(app, Notifications.ID_ACCESSIBILITY)
            return
        }
        if (!automationEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (lastAccessibilityAlertAt != 0L && now - lastAccessibilityAlertAt < ALERT_COOLDOWN_MS) return
        lastAccessibilityAlertAt = now
        Notifications.alertAccessibility(app)
    }

    /** Служба подключилась — снимаем старое уведомление и сбрасываем паузу. */
    fun onAccessibilityConnected(context: Context) {
        lastAccessibilityAlertAt = 0L
        Notifications.cancel(context.applicationContext, Notifications.ID_ACCESSIBILITY)
    }

    private fun probeIfDue(context: Context, now: Long) {
        if (now - lastProbeAt < PROBE_INTERVAL_MS) return
        lastProbeAt = now
        if (!probing.compareAndSet(false, true)) return

        executor.execute {
            try {
                when (probeThroughVpn(context)) {
                    Probe.OK -> {
                        strategyFailures = 0
                        Notifications.cancel(context, Notifications.ID_STRATEGY)
                    }

                    Probe.FAIL -> {
                        strategyFailures++
                        if (strategyFailures >= FAILURES_TO_ALERT &&
                            (lastStrategyAlertAt == 0L ||
                                SystemClock.elapsedRealtime() - lastStrategyAlertAt >= ALERT_COOLDOWN_MS)
                        ) {
                            lastStrategyAlertAt = SystemClock.elapsedRealtime()
                            Notifications.alertStrategy(context)
                        }
                    }

                    Probe.NO_VPN -> Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe failed", e)
            } finally {
                probing.set(false)
            }
        }
    }

    private fun probeThroughVpn(context: Context): Probe {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Probe.NO_VPN
        val active = cm.activeNetwork ?: return Probe.NO_VPN
        val caps = cm.getNetworkCapabilities(active) ?: return Probe.NO_VPN
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Probe.NO_VPN

        var connection: HttpURLConnection? = null
        return try {
            // Обычный запрос идёт через активную VPN-сеть. Привязка сокета к сети
            // на Honor/Android 17 даёт EPERM, поэтому не привязываемся.
            connection = URL(PROBE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "ByeDpiAutostart/1.2")
            val code = connection.responseCode
            if (code in 200..399) Probe.OK else Probe.FAIL
        } catch (e: Exception) {
            Log.d(TAG, "probe error: ${e.message}")
            Probe.FAIL
        } finally {
            connection?.disconnect()
        }
    }

    private fun updateInternet(context: Context) {
        val up = isInternetAvailable(context)
        if (up && !internetUp) {
            internetUp = true
            Notifications.cancel(context, Notifications.ID_NO_INTERNET)
            return
        }
        if (!up) {
            internetUp = false
            alertNoInternet(context)
        }
    }

    private fun alertNoInternet(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastNoInternetAlertAt != 0L && now - lastNoInternetAlertAt < ALERT_COOLDOWN_MS) return
        lastNoInternetAlertAt = now
        Notifications.alertNoInternet(context)
    }

    private fun resetStrategy(context: Context) {
        strategyFailures = 0
        Notifications.cancel(context, Notifications.ID_STRATEGY)
    }

    private fun isInternetAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private enum class Probe { OK, FAIL, NO_VPN }
}
