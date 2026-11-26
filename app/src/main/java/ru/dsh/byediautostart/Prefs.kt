package ru.dsh.byediautostart

import android.content.Context

/** Настройки приложения: что считаем целевыми приложениями и включена ли автоматизация. */
class Prefs(context: Context) {

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("byedpi_autostart", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    var targets: Set<String>
        get() = sp.getStringSet(KEY_TARGETS, DEFAULT_TARGETS)?.toSet() ?: DEFAULT_TARGETS
        set(value) = sp.edit().putStringSet(KEY_TARGETS, value).apply()

    /** Приложения, для которых запускается выбранный пользовательский VPN. */
    var vpnTargets: Set<String>
        get() = sp.getStringSet(KEY_VPN_TARGETS, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_VPN_TARGETS, value).apply()

    /** Какой список целей относится к текущему режиму. */
    fun targetsForMode(): Set<String> = when (vpnMode) {
        VPN_MODE_CUSTOM -> if (customVpnPackage != null) vpnTargets else targets
        // «Авто» — все приложения из обоих списков: для каждого выбирается свой VPN.
        VPN_MODE_AUTO -> targets + vpnTargets
        else -> targets
    }

    /** Пакет ByeByeDPI, найденный при первом запуске. */
    var byeDpiPackage: String?
        get() = sp.getString(KEY_BYE_DPI_PACKAGE, null)
        set(value) = sp.edit().putString(KEY_BYE_DPI_PACKAGE, value).apply()

    /** Светлая (false) или тёмная (true) тема MagicOS 11. */
    var darkTheme: Boolean
        get() = sp.getBoolean(KEY_DARK_THEME, false)
        set(value) = sp.edit().putBoolean(KEY_DARK_THEME, value).apply()

    /** Выбор VPN: auto / byedpi / custom. */
    var vpnMode: String
        get() = sp.getString(KEY_VPN_MODE, VPN_MODE_AUTO) ?: VPN_MODE_AUTO
        set(value) = sp.edit().putString(KEY_VPN_MODE, value).apply()

    /** Пакет VPN, выбранного в настройках (Prizrak/Incy/Happ). */
    var customVpnPackage: String?
        get() = sp.getString(KEY_CUSTOM_VPN, null)
        set(value) = sp.edit().putString(KEY_CUSTOM_VPN, value).apply()

    /** Пауза после ухода из приложения: 15000, 10000, 5000 или 0. */
    var shutdownDelayMs: Long
        get() = sp.getLong(KEY_SHUTDOWN_DELAY, 15_000L)
        set(value) = sp.edit().putLong(KEY_SHUTDOWN_DELAY, value).apply()

    /** Оставлять VPN при погашенном экране. */
    var screenOffKeepVpn: Boolean
        get() = sp.getBoolean(KEY_SCREEN_OFF_KEEP, true)
        set(value) = sp.edit().putBoolean(KEY_SCREEN_OFF_KEEP, value).apply()

    /** Обои телефона как фон приложения. */
    var wallpaperBackground: Boolean
        get() = sp.getBoolean(KEY_WALLPAPER, true)
        set(value) = sp.edit().putBoolean(KEY_WALLPAPER, value).apply()

    /** Прозрачность карточек в процентах: 0 — непрозрачные, 90 — почти прозрачные. */
    var cardTransparency: Int
        get() = sp.getInt(KEY_CARD_TRANSPARENCY, 28).coerceIn(0, 90)
        set(value) = sp.edit().putInt(KEY_CARD_TRANSPARENCY, value.coerceIn(0, 90)).apply()

    /** Палитра акцента: teal / violet / emerald / amber / graphite. */
    var palette: String
        get() = sp.getString(KEY_PALETTE, "teal") ?: "teal"
        set(value) = sp.edit().putString(KEY_PALETTE, value).apply()

    /** Прямая ссылка на APK в релизе GitHub для QR. */
    var githubApkUrl: String
        get() {
            val stored = sp.getString(KEY_GITHUB_APK_URL, "") ?: ""
            return if (stored.isBlank() || stored.contains("github.com/.../")) {
                DEFAULT_GITHUB_APK_URL
            } else {
                stored
            }
        }
        set(value) {
            val url = value.trim().ifBlank { DEFAULT_GITHUB_APK_URL }
            sp.edit().putString(KEY_GITHUB_APK_URL, url).apply()
        }

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Когда последний раз предлагали установить ByeByeDPI. */
    var lastByeDpiPrompt: Long
        get() = sp.getLong(KEY_LAST_BYE_DPI_PROMPT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_BYE_DPI_PROMPT, value).apply()

    var latestVersion: String?
        get() = sp.getString(KEY_LATEST_VERSION, null)
        set(value) = sp.edit().putString(KEY_LATEST_VERSION, value).apply()

    var downloadedVersion: String?
        get() = sp.getString(KEY_DOWNLOADED_VERSION, null)
        set(value) = sp.edit().putString(KEY_DOWNLOADED_VERSION, value).apply()

    var downloadedPath: String?
        get() = sp.getString(KEY_DOWNLOADED_PATH, null)
        set(value) = sp.edit().putString(KEY_DOWNLOADED_PATH, value).apply()

    /** Пауза после ухода из приложения: чтобы YouTube → Instagram не рвали VPN. */
    val graceMs: Long
        get() = shutdownDelayMs

    /** Как часто пробуем поднять упавший VPN. */
    val reconnectMs: Long = 5_000L

    init {
        // Старые режимы prizrak/incy превращаем в пользовательский выбор VPN.
        val mode = sp.getString(KEY_VPN_MODE, VPN_MODE_AUTO)
        if (mode == "prizrak" || mode == "incy") {
            sp.edit()
                .putString(KEY_VPN_MODE, VPN_MODE_CUSTOM)
                .putString(
                    KEY_CUSTOM_VPN,
                    if (mode == "prizrak") PrizrakClient.PACKAGE else IncyClient.PACKAGE,
                )
                .apply()
        }
        // Списки должны быть взаимоисключающими: приоритет у списка ByeByeDPI.
        val overlap = vpnTargets intersect targets
        if (overlap.isNotEmpty()) vpnTargets = vpnTargets - overlap
    }

    /**
     * Одноразовая миграция: у старых установок в списке только YouTube и Instagram.
     * Если пользователь не менял список сам — добавляем установленный Telegram.
     */
    fun migrateTargets() {
        if (sp.getBoolean(KEY_TELEGRAM_MIGRATED, false)) return
        val current = targets
        if (current == LEGACY_TARGETS) {
            val installedTelegram = TELEGRAM_PACKAGES.filter { isInstalled(it) }
            if (installedTelegram.isNotEmpty()) {
                targets = current + installedTelegram
            }
        }
        sp.edit().putBoolean(KEY_TELEGRAM_MIGRATED, true).apply()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        appContext.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TARGETS = "targets"
        private const val KEY_VPN_TARGETS = "vpn_targets"
        private const val KEY_BYE_DPI_PACKAGE = "byedpi_package"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_LAST_BYE_DPI_PROMPT = "last_byedpi_prompt"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_DOWNLOADED_VERSION = "downloaded_version"
        private const val KEY_DOWNLOADED_PATH = "downloaded_path"
        private const val KEY_TELEGRAM_MIGRATED = "telegram_migrated"
        private const val KEY_VPN_MODE = "vpn_mode"
        private const val KEY_CUSTOM_VPN = "custom_vpn_package"
        private const val KEY_SHUTDOWN_DELAY = "shutdown_delay_ms"
        private const val KEY_SCREEN_OFF_KEEP = "screen_off_keep_vpn"
        private const val KEY_WALLPAPER = "wallpaper_background"
        private const val KEY_CARD_TRANSPARENCY = "card_transparency"
        private const val KEY_PALETTE = "palette"
        const val DEFAULT_GITHUB_APK_URL =
            "https://github.com/Bayan1377/ByeDpiAutostart/releases/latest/download/ByeDpiAutostart.apk"
        private const val KEY_GITHUB_APK_URL = "github_apk_url"

        const val VPN_MODE_AUTO = "auto"
        const val VPN_MODE_BYEDPI = "byedpi"
        const val VPN_MODE_CUSTOM = "custom"

        private val LEGACY_TARGETS: Set<String> = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
        )

        private val TELEGRAM_PACKAGES: List<String> = TargetPackages.TELEGRAM.toList()

        val DEFAULT_TARGETS: Set<String> = LEGACY_TARGETS + "org.telegram.messenger"

        fun isTelegram(pkg: String): Boolean = pkg in TELEGRAM_PACKAGES
    }
}
