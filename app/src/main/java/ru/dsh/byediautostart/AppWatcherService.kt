package ru.dsh.byediautostart

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.GestureDescription
import androidx.core.content.ContextCompat

/** Единственный без-root способ узнать, какое приложение сейчас на экране. */
class AppWatcherService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var cleanupDone = false
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> AutomationController.onScreenOff()
                Intent.ACTION_SCREEN_ON -> AutomationController.onScreenOn()
            }
        }
    }

    /**
     * Страховка на случай, если система не дала запустить foreground-сервис:
     * служба доступности живёт сама и вполне может тикать вместо него.
     */
    private val fallbackTicker = object : Runnable {
        override fun run() {
            if (!AutomationState.watchdogRunning) AutomationController.onTick()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cleanupDone = false
        instance = this
        AutomationController.attach(this)
        HealthMonitor.onAccessibilityConnected(this)
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenReceiverRegistered = true
        }
        handler.removeCallbacks(fallbackTicker)
        handler.post(fallbackTicker)
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun tapAtFraction(xFraction: Float, yFraction: Float): Boolean {
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels * xFraction, dm.heightPixels * yFraction)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg.isBlank()) return
        AutomationController.onForeground(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(fallbackTicker)
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(fallbackTicker)
        if (instance === this) instance = null
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (cleanupDone) return
        cleanupDone = true
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                // уже отвязан
            }
            screenReceiverRegistered = false
        }
        AutomationController.onWatcherDisabled()
    }

    companion object {
        @Volatile
        var instance: AppWatcherService? = null

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            @Suppress("DEPRECATION")
            val listed = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true
            if (listed) return true

            // Android 16/17 иногда отдаёт список без resolveInfo — сверяемся с secure settings.
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { flat ->
                ComponentName.unflattenFromString(flat)?.packageName == context.packageName
            }
        }
    }
}
