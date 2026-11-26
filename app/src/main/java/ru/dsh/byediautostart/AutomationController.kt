package ru.dsh.byediautostart

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Связывает события AccessibilityService, настройки и watchdog.
 *
 * Все методы вызываются на главном потоке (события доступности, тик сервиса, UI),
 * поэтому достаточно @Synchronized вместо отдельных блокировок.
 */
object AutomationController {

    private const val TAG = "AutomationController"

    enum class Provider { NONE, BYEDPI, PRIZRAK, INCY, HAPP, HAPP_PLUS }

    private var appContext: Context? = null
    private var prefs: Prefs? = null
    private var core: AutomationCore? = null
    private var vpn: VpnMonitor? = null
    private var neutral: Set<String> = emptySet()

    private var lastStartAt = 0L
    private var lastStopAt = 0L
    private var lastMissingAlertAt = 0L
    private var startAttempts = 0
    private var stopAttempts = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeProvider = Provider.NONE
    private var desiredProvider = Provider.NONE
    private var pendingStart = false
    private var lastForegroundPackage: String? = null
    private var prizrakBackAttempts = 0
    private var screenOff = false

    @Synchronized
    fun reloadSettings() {
        rebuild()
        val c = core ?: return
        if (c.targetActive) applyProvider(c.lastTargetPackage)
    }

    @Synchronized
    fun onScreenOff() {
        screenOff = true
        val p = prefs ?: return
        if (p.screenOffKeepVpn) return
        if (core?.targetActive != true) return
        if (activeProvider == Provider.NONE) return
        stopProvider(activeProvider)
        activeProvider = Provider.NONE
        pendingStart = false
        mainHandler.removeCallbacks(providerStarter)
        mainHandler.removeCallbacks(dismissPrizrak)
    }

    @Synchronized
    fun onScreenOn() {
        screenOff = false
        val c = core ?: return
        if (!c.targetActive) return
        if (activeProvider == Provider.NONE) applyProvider(c.lastTargetPackage)
    }

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = Prefs(app)
        vpn = VpnMonitor(app)
        neutral = buildNeutralPackages(app)
        if (AppWatcherService.isEnabled(app)) {
            HealthMonitor.onAccessibilityConnected(app)
        }
        rebuild()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        val app = appContext ?: return
        val p = prefs ?: return
        p.enabled = value
        if (!value) {
            stopProvider(activeProvider)
            activeProvider = Provider.NONE
            desiredProvider = Provider.NONE
            pendingStart = false
            mainHandler.removeCallbacks(providerStarter)
            mainHandler.removeCallbacks(dismissPrizrak)
            WatchdogService.stop(app)
            core?.reset()
            AutomationState.targetPackage = null
            AutomationState.watchdogRunning = false
        }
    }

    @Synchronized
    fun reloadTargets() {
        if (appContext == null) return
        rebuild()
    }

    /** Пользователь сменил VPN в настройках — сразу переключаем активный сценарий. */
    @Synchronized
    fun reloadProvider() {
        val c = core ?: return
        if (!c.targetActive) return
        applyProvider(c.lastTargetPackage)
    }

    /** Сменился режим/список целей: пересобираем core и заново оцениваем открытое приложение. */
    @Synchronized
    fun reloadMode() {
        val app = appContext ?: return
        stopProvider(activeProvider)
        activeProvider = Provider.NONE
        desiredProvider = Provider.NONE
        pendingStart = false
        mainHandler.removeCallbacks(providerStarter)
        mainHandler.removeCallbacks(dismissPrizrak)
        core?.reset()
        rebuild()
        WatchdogService.stop(app)
        AutomationState.targetPackage = null
        lastForegroundPackage?.let { onForeground(it) }
    }

    @Synchronized
    fun onForeground(pkg: String) {
        val app = appContext ?: return
        val p = prefs ?: return
        if (!p.enabled) return
        val c = core ?: return
        lastForegroundPackage = pkg

        val previousTarget = c.lastTargetPackage
        val now = SystemClock.elapsedRealtime()
        val result = c.onForeground(pkg, now)
        val target = c.lastTargetPackage

        when (result) {
            AutomationCore.Foreground.ENTERED_TARGET -> {
                startAttempts = 0
                stopAttempts = 0
                lastStartAt = 0
                AutomationState.targetPackage = target
                WatchdogService.start(app)
                applyProvider(target)
            }

            AutomationCore.Foreground.LEFT_TARGET -> {
                AutomationState.targetPackage = null
                if (p.shutdownDelayMs == 0L && activeProvider != Provider.NONE) {
                    stopProvider(activeProvider)
                    activeProvider = Provider.NONE
                    pendingStart = false
                    mainHandler.removeCallbacks(providerStarter)
                    mainHandler.removeCallbacks(dismissPrizrak)
                    finishCycle(c)
                } else {
                    WatchdogService.start(app)
                }
            }

            AutomationCore.Foreground.NONE -> {
                if (c.targetActive && target != null && target != previousTarget) {
                    // Переключение Telegram → YouTube/Instagram и обратно.
                    applyProvider(target)
                }
            }

            AutomationCore.Foreground.NEUTRAL -> Unit
        }
    }

    /**
     * Выбирает VPN-приложение для текущей цели и переключает его.
     * Два VPN одновременно работать не могут, поэтому старый сначала выключается,
     * новый запускается через паузу.
     */
    private fun applyProvider(target: String?) {
        val wanted = resolveProvider(target)
        desiredProvider = wanted
        if (wanted == activeProvider) return

        stopProvider(activeProvider)
        activeProvider = Provider.NONE
        pendingStart = true
        mainHandler.removeCallbacks(providerStarter)
        mainHandler.removeCallbacks(dismissPrizrak)
        mainHandler.postDelayed(providerStarter, 1_500L)
        lastStartAt = 0
    }

    private val providerStarter = Runnable {
        pendingStart = false
        if (core?.targetActive == true) startActiveIfDue()
    }

    private fun startActiveIfDue() {
        val app = appContext ?: return
        val p = prefs ?: return
        val c = core ?: return

        if (!c.targetActive) return
        if (vpn?.isVpnUp() == true) {
            if (activeProvider == Provider.NONE) activeProvider = desiredProvider
            startAttempts = 0
            return
        }
        if (pendingStart || desiredProvider == Provider.NONE) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAt < p.reconnectMs) return
        lastStartAt = now

        if (!HealthMonitor.hasInternet(app)) {
            HealthMonitor.reportNoInternet(app)
            return
        }

        activeProvider = desiredProvider
        startAttempts++
        when (activeProvider) {
            Provider.BYEDPI -> startByeDpi(app)
            Provider.PRIZRAK -> if (PrizrakClient.start(app)) schedulePrizrakDismiss()
            Provider.INCY -> IncyClient.start(app)
            Provider.HAPP -> HappClient.start(app)
            Provider.HAPP_PLUS -> HappPlusClient.start(app)
            Provider.NONE -> Unit
        }
        if (startAttempts == 3) Notifications.alertVpn(app)
    }

    private fun startByeDpi(app: Context) {
        val pkg = resolveByeDpiPackage(app)
        if (pkg == null) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMissingAlertAt >= 10 * 60_000L) {
                lastMissingAlertAt = now
                Notifications.alertMissing(app)
            }
            return
        }
        val sent = ByeDpiClient.start(app, pkg)
        Log.i(TAG, "ByeByeDPI start -> $pkg, попытка $startAttempts, отправлено=$sent")
    }

    private fun stopProvider(provider: Provider) {
        val app = appContext ?: return
        when (provider) {
            Provider.BYEDPI -> {
                val pkg = resolveByeDpiPackage(app) ?: return
                if (vpn?.isVpnUp() == true) {
                    ByeDpiClient.stop(app, pkg)
                    ByeDpiClient.killProcess(app, pkg)
                }
            }

            Provider.PRIZRAK -> PrizrakClient.stop(app)
            Provider.INCY -> IncyClient.stop(app)
            Provider.HAPP -> HappClient.stop(app)
            Provider.HAPP_PLUS -> HappPlusClient.stop(app)
            Provider.NONE -> Unit
        }
    }

    /**
     * «Призрак Box» после START показывает свой экран поверх Telegram.
     * Возвращаем пользователя назад, как только убедимся, что сверху именно Призрак.
     */
    private val dismissPrizrak = object : Runnable {
        override fun run() {
            if (activeProvider != Provider.PRIZRAK || core?.targetActive != true) return
            if (lastForegroundPackage != PrizrakClient.PACKAGE) return
            AppWatcherService.instance?.performBack()
            if (prizrakBackAttempts++ < 2) {
                mainHandler.postDelayed(this, 900L)
            }
        }
    }

    private fun schedulePrizrakDismiss() {
        prizrakBackAttempts = 0
        mainHandler.removeCallbacks(dismissPrizrak)
        mainHandler.postDelayed(dismissPrizrak, 1_200L)
    }

    @Synchronized
    fun onTick() {
        val app = appContext ?: return
        val p = prefs ?: return
        val c = core ?: return

        if (!p.enabled) {
            WatchdogService.stop(app)
            return
        }

        val now = SystemClock.elapsedRealtime()
        val up = vpn?.isVpnUp() == true
        AutomationState.vpnUp = up

        HealthMonitor.onTick(app, c.targetActive, up)

        if (c.targetActive) {
            if (screenOff && !p.screenOffKeepVpn) return
            val target = c.lastTargetPackage
            val wanted = resolveProvider(target)
            if (wanted != desiredProvider) {
                applyProvider(target)
                return
            }
            if (up) {
                if (activeProvider == Provider.NONE) activeProvider = desiredProvider
                startAttempts = 0
                Notifications.cancel(app, Notifications.ID_VPN)
            } else {
                startActiveIfDue()
            }
            return
        }

        if (!c.hasState()) {
            WatchdogService.stop(app)
            return
        }

        if (!c.shouldStop(now)) {
            if (!up) WatchdogService.stop(app)
            return
        }

        if (activeProvider == Provider.NONE) {
            // VPN поднят не нами (например, после перезапуска) — не трогаем его.
            finishCycle(c)
            return
        }

        if (!up) {
            stopProvider(activeProvider)
            activeProvider = Provider.NONE
            finishCycle(c)
            return
        }

        val retryDelay = if (stopAttempts >= 3) 30_000L else 4_000L
        if (now - lastStopAt >= retryDelay) {
            lastStopAt = now
            stopAttempts++
            stopProvider(activeProvider)
            if (stopAttempts == 3) Notifications.alertStopProblem(app)
        }
    }

    fun isTargetActive(): Boolean = core?.targetActive == true

    /**
     * Службу доступности выключили или система её отвязала. Без неё мы больше не узнаем,
     * когда приложение закроется, поэтому лучше погасить VPN, чем оставить его навсегда.
     */
    @Synchronized
    fun onWatcherDisabled() {
        val app = appContext ?: return
        val p = prefs ?: return
        core?.reset()
        AutomationState.targetPackage = null
        AutomationState.watchdogRunning = false
        if (p.enabled) stopProvider(activeProvider)
        activeProvider = Provider.NONE
        desiredProvider = Provider.NONE
        pendingStart = false
        mainHandler.removeCallbacks(providerStarter)
        mainHandler.removeCallbacks(dismissPrizrak)
        WatchdogService.stop(app)
        Notifications.cancel(app, Notifications.ID_VPN)
        Notifications.cancel(app, Notifications.ID_STRATEGY)
        Notifications.cancel(app, Notifications.ID_NO_INTERNET)
    }

    private fun finishCycle(core: AutomationCore) {
        val app = appContext ?: return
        core.markStopped()
        AutomationState.targetPackage = null
        AutomationState.watchdogRunning = false
        activeProvider = Provider.NONE
        desiredProvider = Provider.NONE
        pendingStart = false
        mainHandler.removeCallbacks(providerStarter)
        mainHandler.removeCallbacks(dismissPrizrak)
        WatchdogService.stop(app)
        Notifications.cancel(app, Notifications.ID_VPN)
        Notifications.cancel(app, Notifications.ID_STRATEGY)
        Notifications.cancel(app, Notifications.ID_NO_INTERNET)
    }

    private fun resolveProvider(target: String?): Provider {
        val app = appContext ?: return Provider.BYEDPI
        val p = prefs ?: return Provider.BYEDPI
        val prizrakInstalled = PrizrakClient.isInstalled(app)
        val incyInstalled = IncyClient.isInstalled(app)

        return when (p.vpnMode) {
            Prefs.VPN_MODE_BYEDPI -> Provider.BYEDPI
            Prefs.VPN_MODE_CUSTOM -> when (VpnAppRegistry.find(app, p.customVpnPackage)?.kind) {
                VpnAppRegistry.Kind.PRIZRAK -> Provider.PRIZRAK
                VpnAppRegistry.Kind.INCY -> Provider.INCY
                VpnAppRegistry.Kind.HAPP -> Provider.HAPP
                VpnAppRegistry.Kind.HAPP_PLUS -> Provider.HAPP_PLUS
                null -> Provider.BYEDPI
            }

            else -> {
                val custom = VpnAppRegistry.find(app, p.customVpnPackage)
                if (custom != null && target != null && target in p.vpnTargets) {
                    when (custom.kind) {
                        VpnAppRegistry.Kind.PRIZRAK -> Provider.PRIZRAK
                        VpnAppRegistry.Kind.INCY -> Provider.INCY
                        VpnAppRegistry.Kind.HAPP -> Provider.HAPP
                        VpnAppRegistry.Kind.HAPP_PLUS -> Provider.HAPP_PLUS
                    }
                } else if (target != null && TargetPackages.isTelegram(target) && prizrakInstalled) {
                    Provider.PRIZRAK
                } else {
                    Provider.BYEDPI
                }
            }
        }
    }

    private fun resolveByeDpiPackage(app: Context): String? {
        val p = prefs ?: return null
        return p.byeDpiPackage ?: ByeDpiClient.findPackage(app)?.also { p.byeDpiPackage = it }
    }

    private fun rebuild() {
        val p = prefs ?: return
        val old = core
        val fresh = AutomationCore(p.targetsForMode(), neutral, p.graceMs)
        old?.let { fresh.restore(it.targetActive, it.lastTargetPackage, it.pendingStopAt()) }
        core = fresh
    }

    private fun buildNeutralPackages(app: Context): Set<String> {
        val set = mutableSetOf(
            app.packageName,
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
        ByeDpiClient.findPackage(app)?.let { set += it }
        set += PrizrakClient.PACKAGE
        set += IncyClient.PACKAGE
        HappClient.findPackage(app)?.let { set += it }
        if (HappPlusClient.isInstalled(app)) set += HappPlusClient.PACKAGE
        try {
            val imm = app.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { set += it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось получить список клавиатур", e)
        }
        return set
    }
}
