package ru.dsh.byediautostart

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Экран настроек: VPN, задержка выключения, поведение при погашенном экране, оформление. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var updating = false
    private lateinit var rootView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeRes(this))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootView = root
        WallpaperBackground.apply(this, root)
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.settings_title)
            setTitleTextColor(Color.WHITE)
            setBackgroundResource(ThemeManager.headerRes(this@SettingsActivity))
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.settings_menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_app_update) {
                    AppUpdateChecker.checkNow(this@SettingsActivity)
                    true
                } else {
                    false
                }
            }
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64),
            ).apply {
                setMargins(dp(12), dp(8), dp(12), 0)
            },
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildVpnSection(content)
        buildDelaySection(content)
        buildScreenSection(content)
        buildAppearanceSection(content)
        buildToolsSection(content)
        buildByeDpiDescription(content)
        buildGithubSection(content)

        setContentView(root)
        StatusBarCard.apply(this, root)
        CardStyler.apply(this, root)
    }

    private fun buildVpnSection(parent: LinearLayout) {
        val body = addCard(parent, getString(R.string.settings_vpn_title))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val apps = VpnAppRegistry.supported(this)
        val byPackage = apps.associateBy { it.packageName }
        val noneId = View.generateViewId()
        val none = radio(getString(R.string.settings_vpn_none), noneId, null)
        group.addView(none)

        var selectedId = noneId
        for (app in apps) {
            val id = View.generateViewId()
            val title = if (app.installed) {
                app.label
            } else {
                getString(R.string.settings_vpn_not_installed, app.label)
            }
            val item = radio(title, id, app.packageName)
            group.addView(item)
            if (app.installed && app.packageName == prefs.customVpnPackage) selectedId = id
        }
        if (apps.none { it.installed }) {
            body.addView(hint(getString(R.string.settings_vpn_download_hint)))
        }

        var currentId = selectedId
        updating = true
        group.check(selectedId)
        updating = false
        group.setOnCheckedChangeListener { _, id ->
            if (updating) return@setOnCheckedChangeListener
            val button = group.findViewById<RadioButton>(id)
            val pkg = button?.tag as? String
            val app = pkg?.let { byPackage[it] }
            if (app != null && !app.installed) {
                updating = true
                group.check(currentId)
                updating = false
                showVpnDownloadDialog(app)
                return@setOnCheckedChangeListener
            }
            currentId = id
            prefs.customVpnPackage = pkg
            prefs.vpnMode = if (pkg == null) Prefs.VPN_MODE_AUTO else Prefs.VPN_MODE_CUSTOM
            AutomationController.reloadMode()
        }
        body.addView(group)
    }

    private fun showVpnDownloadDialog(app: VpnAppRegistry.App) {
        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setMessage(R.string.settings_vpn_download_message)
            .setPositiveButton(R.string.settings_vpn_download) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.downloadUrl)))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.settings_vpn_download_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildDelaySection(parent: LinearLayout) {
        val body = addCard(parent, getString(R.string.settings_delay_title))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            15_000L to getString(R.string.settings_delay_15),
            10_000L to getString(R.string.settings_delay_10),
            5_000L to getString(R.string.settings_delay_5),
            0L to getString(R.string.settings_delay_now),
        )
        var checkedId = -1
        for ((value, label) in options) {
            val id = View.generateViewId()
            val item = radio(label, id, value)
            group.addView(item)
            if (prefs.shutdownDelayMs == value) checkedId = id
        }
        if (checkedId != -1) {
            updating = true
            group.check(checkedId)
            updating = false
        }
        group.setOnCheckedChangeListener { _, id ->
            if (updating) return@setOnCheckedChangeListener
            val value = group.findViewById<RadioButton>(id)?.tag as? Long ?: return@setOnCheckedChangeListener
            prefs.shutdownDelayMs = value
            AutomationController.reloadSettings()
        }
        body.addView(group)
    }

    private fun buildScreenSection(parent: LinearLayout) {
        val body = addCard(parent, getString(R.string.settings_screen_title))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val keepId = View.generateViewId()
        val offId = View.generateViewId()
        group.addView(radio(getString(R.string.settings_screen_keep), keepId, true))
        group.addView(radio(getString(R.string.settings_screen_off), offId, false))
        group.check(if (prefs.screenOffKeepVpn) keepId else offId)
        group.setOnCheckedChangeListener { _, id ->
            prefs.screenOffKeepVpn = id == keepId
            AutomationController.reloadSettings()
        }
        body.addView(group)
    }

    private fun buildAppearanceSection(parent: LinearLayout) {
        val body = addCard(parent, getString(R.string.settings_appearance_title))
        val wallpaper = MaterialSwitch(this).apply {
            text = getString(R.string.settings_wallpaper)
            isChecked = prefs.wallpaperBackground
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        wallpaper.setOnCheckedChangeListener { _, checked ->
            prefs.wallpaperBackground = checked
            if (checked && !android.os.Environment.isExternalStorageManager()) {
                Toast.makeText(this, R.string.settings_wallpaper_permission, Toast.LENGTH_LONG).show()
                try {
                    startActivity(
                        android.content.Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (ignored: Exception) {
                        // останется системный фон
                    }
                }
            }
            recreate()
        }
        body.addView(wallpaper)

        val dark = MaterialSwitch(this).apply {
            text = getString(R.string.settings_dark_theme)
            isChecked = prefs.darkTheme
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        dark.setOnCheckedChangeListener { _, checked ->
            if (checked == prefs.darkTheme) return@setOnCheckedChangeListener
            prefs.darkTheme = checked
            ThemeManager.apply(prefs)
            recreate()
        }
        body.addView(dark)

        val paletteTitle = TextView(this).apply {
            text = getString(R.string.settings_palette_title)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, dp(10), 0, 0)
        }
        body.addView(paletteTitle)
        val palettes = listOf(
            "teal" to R.color.palette_teal,
            "blue" to R.color.palette_blue,
            "violet" to R.color.palette_violet,
            "emerald" to R.color.palette_emerald,
            "amber" to R.color.palette_amber,
            "graphite" to R.color.palette_graphite,
        )
        val names = mapOf(
            "teal" to R.string.palette_teal,
            "blue" to R.string.palette_blue,
            "violet" to R.string.palette_violet,
            "emerald" to R.string.palette_emerald,
            "amber" to R.string.palette_amber,
            "graphite" to R.string.palette_graphite,
        )
        val paletteGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        var checkedPaletteId = -1
        for ((key, colorRes) in palettes) {
            val id = View.generateViewId()
            val item = RadioButton(this).apply {
                this.id = id
                text = getString(names.getValue(key))
                tag = key
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                buttonTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@SettingsActivity, colorRes),
                )
            }
            paletteGroup.addView(item)
            if (prefs.palette == key) checkedPaletteId = id
        }
        if (checkedPaletteId != -1) paletteGroup.check(checkedPaletteId)
        paletteGroup.setOnCheckedChangeListener { _, id ->
            val key = paletteGroup.findViewById<RadioButton>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            if (key != prefs.palette) {
                prefs.palette = key
                recreate()
            }
        }
        body.addView(paletteGroup)

        val transparencyLabel = TextView(this).apply {
            text = getString(R.string.settings_card_transparency, prefs.cardTransparency)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, dp(8), 0, 0)
        }
        body.addView(transparencyLabel)
        val transparency = Slider(this).apply {
            valueFrom = 0f
            valueTo = 90f
            stepSize = 1f
            value = prefs.cardTransparency.toFloat()
        }
        transparency.addOnChangeListener { _, value, _ ->
            prefs.cardTransparency = value.toInt()
            transparencyLabel.text = getString(R.string.settings_card_transparency, value.toInt())
            CardStyler.apply(this@SettingsActivity, rootView)
        }
        body.addView(transparency)
    }

    private fun buildToolsSection(parent: LinearLayout) {
        val tools = layoutInflater.inflate(R.layout.view_tools, parent, false)
        parent.addView(tools)
        tools.findViewById<View>(R.id.btnOpenByeDpi).setOnClickListener {
            ToolsActions.openByeDpi(this)
        }
        tools.findViewById<View>(R.id.btnShareApk).setOnClickListener {
            ToolsActions.shareApk(this)
        }
        tools.findViewById<View>(R.id.btnQrShare).setOnClickListener {
            ToolsActions.showQrShare(this)
        }
        tools.findViewById<View>(R.id.btnDefaultApps).setOnClickListener {
            ToolsActions.resetDefaultTargets(this)
        }
    }

    private fun buildByeDpiDescription(parent: LinearLayout) {
        val body = addCard(parent, getString(R.string.byedpi_setup_title))
        body.addView(hint(getString(R.string.byedpi_setup_text)))
        val open = MaterialButton(this).apply {
            text = getString(R.string.byedpi_open)
            setOnClickListener { ToolsActions.openByeDpi(this@SettingsActivity) }
        }
        body.addView(open)
    }

    private fun buildGithubSection(parent: LinearLayout) {        val body = addCard(parent, getString(R.string.settings_github_title))
        body.addView(hint(getString(R.string.settings_github_hint)))
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.githubApkUrl)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            hint = "https://github.com/.../ByeDpiAutostart.apk"
        }
        body.addView(input)
        val save = MaterialButton(this).apply {
            text = getString(R.string.settings_save)
            setOnClickListener {
                prefs.githubApkUrl = input.text.toString().trim()
                Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
        }
        body.addView(save)
    }

    private fun addCard(parent: LinearLayout, title: String): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.card_bg))
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@SettingsActivity, R.color.card_stroke)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
        }
        body.addView(titleView)
        card.addView(body)
        parent.addView(card)
        return body
    }

    private fun radio(label: String, id: Int, tag: Any?): RadioButton =
        RadioButton(this).apply {
            this.id = id
            text = label
            this.tag = tag
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            buttonTintList = ColorStateList.valueOf(
                ThemeManager.accentColor(this@SettingsActivity),
            )
        }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
