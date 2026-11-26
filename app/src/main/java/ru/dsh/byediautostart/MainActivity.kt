package ru.dsh.byediautostart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.dsh.byediautostart.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var vpnMonitor: VpnMonitor

    private val handler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            updateState()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeRes(this))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarCard.apply(this, binding.root)
        binding.toolbar.setBackgroundResource(ThemeManager.headerRes(this))
        WallpaperBackground.apply(this, binding.root)

        alertsCard = findViewById(R.id.alertsCard)
        tvAlertsSummary = findViewById(R.id.tvAlertsSummary)
        val openAlerts = View.OnClickListener { openAlerts() }
        alertsCard.setOnClickListener(openAlerts)
        findViewById<View>(R.id.btnAlerts).setOnClickListener(openAlerts)

        AutomationController.attach(this)
        prefs = Prefs(this)
        vpnMonitor = VpnMonitor(this)
        UpdateChecker.init(this)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.action_update -> {
                    UpdateChecker.checkNow(this)
                    true
                }

                R.id.action_theme -> {
                    ThemeManager.toggle(this, prefs)
                    true
                }

                R.id.action_settings -> {
                    openSettings()
                    true
                }

                else -> false
            }
        }
        updateThemeIcon()

        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
        binding.btnByeDpi.setOnClickListener { openByeDpi() }
        findViewById<View>(R.id.btnPickApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<View>(R.id.btnPickVpnApps).setOnClickListener {
            startActivity(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_LIST, AppPickerActivity.LIST_VPN),
            )
        }

        binding.switchAutomation.setOnCheckedChangeListener { _, checked ->
            AutomationController.setEnabled(checked)
            updateSummary()
            if (checked && !AppWatcherService.isEnabled(this)) showAccessibilityDialog()
        }

        requestNotificationPermissionIfNeeded()
        setupVpnChoice()
        UpdateChecker.maybeCheck(this)
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.refreshAfterInstall(this)
        refreshPermissions()
        WallpaperBackground.apply(this, binding.root)
        CardStyler.apply(this, binding.root)
        setupVpnChoice()
        updateThemeIcon()
        ByeDpiInstaller.maybeOffer(this)
        warnIfDoNotDisturb()
        Notifications.clearProblems(this)
        refreshAlerts()
        updateState()
        updateSummary()
        handler.post(statusTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
    }

    private fun refreshPermissions() {
        val accessibility = AppWatcherService.isEnabled(this)
        val overlay = Settings.canDrawOverlays(this)
        val battery = isBatteryOptimizationIgnored()
        val byeDpi = ByeDpiClient.findPackage(this)
        prefs.byeDpiPackage = byeDpi

        binding.tvAccessibility.text = getString(if (accessibility) R.string.accessibility_ok else R.string.accessibility_no)
        binding.tvOverlay.text = getString(if (overlay) R.string.overlay_ok else R.string.overlay_no)
        binding.tvBattery.text = getString(if (battery) R.string.battery_ok else R.string.battery_no)
        binding.tvByeDpi.text = getString(if (byeDpi != null) R.string.byedpi_ok else R.string.byedpi_no)
        binding.btnByeDpi.setText(if (byeDpi != null) R.string.btn_byedpi else R.string.btn_install)
        binding.btnOverlay.setText(if (overlay) R.string.btn_byedpi else R.string.btn_overlay)
        binding.btnBattery.setText(if (battery) R.string.btn_byedpi else R.string.btn_battery)
        HealthMonitor.onPermissions(this, accessibility, prefs.enabled)

        binding.switchAutomation.setOnCheckedChangeListener(null)
        binding.switchAutomation.isChecked = prefs.enabled
        binding.switchAutomation.setOnCheckedChangeListener { _, checked ->
            AutomationController.setEnabled(checked)
            updateSummary()
            if (checked && !AppWatcherService.isEnabled(this)) showAccessibilityDialog()
        }
        updateSummary()
    }

    private fun updateSummary() {
        binding.tvAutomationSummary.text = getString(
            if (prefs.enabled) R.string.automation_summary_on else R.string.automation_summary_off,
            prefs.targetsForMode().size,
        )
    }

    private fun updateState() {
        val up = vpnMonitor.isVpnUp()
        AutomationState.vpnUp = up
        binding.tvVpn.text = getString(if (up) R.string.vpn_state_on else R.string.vpn_state_off)

        val target = AutomationState.targetPackage
        binding.tvTarget.text = if (target == null) {
            getString(R.string.target_state_none)
        } else {
            getString(R.string.target_state_active, appLabel(target))
        }

        binding.tvService.text = getString(
            if (AutomationState.watchdogRunning) R.string.service_state_running else R.string.service_state_idle
        )
        renderUpdate(UpdateChecker.state)
    }

    private fun renderUpdate(state: UpdateChecker.UiState) {
        val card = binding.updateCard
        if (state.status == UpdateChecker.Status.IDLE) {
            card.isVisible = false
            return
        }
        card.isVisible = true

        val progress = binding.progressUpdate
        val button = binding.btnUpdate
        progress.isVisible = false
        button.isVisible = false
        button.setOnClickListener(null)

        when (state.status) {
            UpdateChecker.Status.CHECKING -> {
                binding.tvUpdateTitle.setText(R.string.update_checking_title)
                binding.tvUpdateText.setText(R.string.update_checking_text)
                progress.isVisible = true
                progress.isIndeterminate = true
            }

            UpdateChecker.Status.DOWNLOADING -> {
                binding.tvUpdateTitle.text = getString(R.string.update_downloading_title, state.version)
                binding.tvUpdateText.text = getString(R.string.update_downloading_text, state.progress)
                progress.isVisible = true
                progress.isIndeterminate = false
                progress.progress = state.progress
            }

            UpdateChecker.Status.READY -> {
                binding.tvUpdateTitle.setText(R.string.update_ready_title)
                binding.tvUpdateText.text = getString(R.string.update_ready_text, state.version)
                button.isVisible = true
                button.setText(R.string.btn_update_install)
                button.setOnClickListener { UpdateChecker.install(this) }
            }

            UpdateChecker.Status.NEED_PERMISSION -> {
                binding.tvUpdateTitle.setText(R.string.update_permission_title)
                binding.tvUpdateText.setText(R.string.update_permission_text)
                button.isVisible = true
                button.setText(R.string.btn_update_allow)
                button.setOnClickListener { UpdateChecker.install(this) }
            }

            UpdateChecker.Status.ERROR -> {
                binding.tvUpdateTitle.setText(R.string.update_error_title)
                binding.tvUpdateText.text = getString(R.string.update_error_text, state.message)
                button.isVisible = true
                button.setText(R.string.btn_update_retry)
                button.setOnClickListener { UpdateChecker.retry(this) }
            }

            UpdateChecker.Status.IDLE -> Unit
        }
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.toast_settings_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openByeDpi() {
        val pkg = prefs.byeDpiPackage ?: ByeDpiClient.findPackage(this)
        if (pkg == null) {
            ByeDpiInstaller.offer(this)
            return
        }
        prefs.byeDpiPackage = pkg
        if (ByeDpiClient.openApp(this, pkg)) {
            Toast.makeText(this, R.string.toast_byedpi_opened, Toast.LENGTH_LONG).show()
        }
    }

    private fun showAccessibilityDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_accessibility_title)
            .setMessage(R.string.dialog_accessibility_text)
            .setPositiveButton(R.string.dialog_open) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun setupVpnChoice() {
        vpnToggle = findViewById(R.id.toggleVpn)
        tvVpnChoiceHint = findViewById(R.id.tvVpnChoiceHint)
        val custom = VpnAppRegistry.find(this, prefs.customVpnPackage)
        val customButton = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnVpnCustom)
        customButton.text = custom?.label ?: getString(R.string.vpn_choice_plus)

        val customSelected = prefs.vpnMode == Prefs.VPN_MODE_CUSTOM && custom != null
        val selected = when {
            customSelected -> R.id.btnVpnCustom
            prefs.vpnMode == Prefs.VPN_MODE_BYEDPI -> R.id.btnVpnByedpi
            else -> R.id.btnVpnAuto
        }

        updatingVpnChoice = true
        vpnToggle.clearOnButtonCheckedListeners()
        vpnToggle.check(selected)
        updatingVpnChoice = false

        vpnToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingVpnChoice) return@addOnButtonCheckedListener
            if (checkedId == R.id.btnVpnCustom && custom == null) {
                openSettings()
                updatingVpnChoice = true
                vpnToggle.check(selected)
                updatingVpnChoice = false
                return@addOnButtonCheckedListener
            }
            prefs.vpnMode = when (checkedId) {
                R.id.btnVpnByedpi -> Prefs.VPN_MODE_BYEDPI
                R.id.btnVpnCustom -> Prefs.VPN_MODE_CUSTOM
                else -> Prefs.VPN_MODE_AUTO
            }
            AutomationController.reloadMode()
            updateVpnChoiceHint()
            updateSummary()
        }
        updateVpnChoiceHint()
    }

    private fun updateVpnChoiceHint() {
        val custom = VpnAppRegistry.find(this, prefs.customVpnPackage)
        val vpnTargets = prefs.vpnTargets
        val vpnCount = vpnTargets.size
        val byedpiTargets = prefs.targets
        val byedpiCount = byedpiTargets.size
        val singleVpn = vpnTargets.singleOrNull()?.let { appLabel(it) }
        val singleByeDpi = byedpiTargets.singleOrNull()?.let { appLabel(it) }

        tvVpnChoiceHint.text = when (prefs.vpnMode) {
            Prefs.VPN_MODE_BYEDPI -> when {
                byedpiCount == 1 -> getString(R.string.vpn_choice_single, singleByeDpi, "ByeByeDPI")
                else -> getString(R.string.vpn_choice_byedpi_count, byedpiCount)
            }

            Prefs.VPN_MODE_CUSTOM -> when {
                custom == null && !prefs.customVpnPackage.isNullOrBlank() ->
                    getString(R.string.vpn_choice_selected_missing)
                custom == null -> getString(R.string.vpn_choice_hint_none)
                vpnCount == 0 -> getString(R.string.vpn_choice_custom_empty, custom.label)
                vpnCount == 1 -> getString(R.string.vpn_choice_single, singleVpn, custom.label)
                else -> getString(R.string.vpn_choice_custom_count, vpnCount, custom.label)
            }

            else -> {
                val parts = mutableListOf<String>()
                val telegramSelected = byedpiTargets.any { TargetPackages.isTelegram(it) }
                if (telegramSelected && PrizrakClient.isInstalled(this)) {
                    parts += getString(R.string.vpn_choice_auto_telegram)
                }
                if (custom == null && !prefs.customVpnPackage.isNullOrBlank()) {
                    parts += getString(R.string.vpn_choice_selected_missing)
                } else if (custom != null && vpnCount == 1) {
                    parts += getString(R.string.vpn_choice_single, singleVpn, custom.label)
                } else if (custom != null && vpnCount > 1) {
                    parts += getString(R.string.vpn_choice_auto_custom, vpnCount, custom.label)
                }
                val byeDpiRest = byedpiTargets.any { !TargetPackages.isTelegram(it) }
                if (byeDpiRest) parts += getString(R.string.vpn_choice_auto_rest)
                when {
                    parts.isNotEmpty() -> parts.joinToString(", ")
                    byedpiCount == 1 -> getString(R.string.vpn_choice_single, singleByeDpi, "ByeByeDPI")
                    else -> getString(R.string.vpn_choice_all_byedpi)
                }
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun updateThemeIcon() {
        val item = binding.toolbar.menu.findItem(R.id.action_theme) ?: return
        item.setIcon(if (prefs.darkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
        item.title = getString(if (prefs.darkTheme) R.string.action_light_theme else R.string.action_dark_theme)
    }

    private fun refreshAlerts() {
        val unread = AlertLog.unreadCount(this)
        val latest = AlertLog.latest(this)
        tvAlertsSummary.text = if (unread > 0) {
            buildString {
                append(getString(R.string.alerts_new, unread))
                if (latest != null) append(" · ").append(latest.title)
            }
        } else {
            getString(R.string.alerts_none)
        }
    }

    private fun openAlerts() {
        startActivity(Intent(this, AlertsActivity::class.java))
    }

    private fun shareApk() {
        val dir = getExternalFilesDir("updates")
        if (dir == null) {
            Toast.makeText(this, R.string.share_apk_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val apk = File(dir, "ByeDpiAutostart.apk")
        try {
            dir.mkdirs()
            File(applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_apk_title)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.share_apk_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQrShare() {
        val github = prefs.githubApkUrl.trim()
        val server = if (github.isEmpty()) QrShareServer(this) else null
        val url = if (github.isNotEmpty()) github else server?.start()
        if (url == null) {
            server?.stop()
            Toast.makeText(this, R.string.qr_no_wifi, Toast.LENGTH_LONG).show()
            return
        }
        val isGithub = github.isNotEmpty()

        val qrSize = (280 * resources.displayMetrics.density).toInt()
        val image = ImageView(this).apply {
            setImageBitmap(QrShareServer.qrBitmap(url, 720))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        val hint = TextView(this).apply {
            text = getString(if (isGithub) R.string.qr_hint_github else R.string.qr_hint, url)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 8, 32, 0)
            addView(
                image,
                LinearLayout.LayoutParams(qrSize, qrSize),
            )
            addView(hint)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_title)
            .setView(container)
            .setPositiveButton(R.string.qr_close, null)
            .setOnDismissListener { server?.stop() }
            .create()
            .also { dialog ->
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.show()
            }
    }

    private fun warnIfDoNotDisturb() {
        if (!prefs.enabled || dndWarned) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return
        dndWarned = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dnd_title)
            .setMessage(R.string.dnd_text)
            .setPositiveButton(R.string.dnd_settings) { _, _ ->
                try {
                    startActivity(Intent("android.settings.ZEN_MODE_SETTINGS"))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
            }
            .setNegativeButton(R.string.dnd_ok, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 100
    }

    private var dndWarned = false
    private lateinit var alertsCard: View
    private lateinit var tvAlertsSummary: TextView
    private lateinit var vpnToggle: MaterialButtonToggleGroup
    private lateinit var tvVpnChoiceHint: TextView
    private var updatingVpnChoice = false
}
