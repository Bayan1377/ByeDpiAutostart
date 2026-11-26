package ru.dsh.byediautostart

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import ru.dsh.byediautostart.databinding.ActivityAppPickerBinding

class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppListAdapter
    private var otherList: Set<String> = emptySet()

    private val isVpnList: Boolean
        get() = intent.getStringExtra(EXTRA_LIST) == LIST_VPN

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeRes(this))
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarCard.apply(this, binding.root)
        binding.toolbar.setBackgroundResource(ThemeManager.headerRes(this))

        prefs = Prefs(this)
        val selected = (if (isVpnList) prefs.vpnTargets else prefs.targets).toMutableSet()
        val otherList = if (isVpnList) prefs.targets else prefs.vpnTargets
        this.otherList = otherList

        adapter = AppListAdapter(selected) { _, _ ->
            if (isVpnList) {
                prefs.vpnTargets = selected.toSet()
                prefs.targets = prefs.targets - selected
            } else {
                prefs.targets = selected.toSet()
                prefs.vpnTargets = prefs.vpnTargets - selected
            }
            AutomationController.reloadTargets()
        }
        adapter.onCountChanged = { count -> binding.tvEmpty.isVisible = count == 0 }

        binding.toolbar.title = getString(if (isVpnList) R.string.pick_vpn_apps_title else R.string.pick_apps_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val columns = when {
            resources.configuration.screenWidthDp >= 1200 -> 4
            resources.configuration.screenWidthDp >= 900 -> 3
            resources.configuration.screenWidthDp >= 600 -> 2
            else -> 1
        }
        binding.rvApps.layoutManager = GridLayoutManager(this, columns)
        binding.rvApps.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        loadApps()
    }

    private fun loadApps() {
        val selected = if (isVpnList) prefs.vpnTargets else prefs.targets
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                emptyList()
            }
            val seen = HashSet<String>()
            val apps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
                if (pkg in otherList) return@mapNotNull null
                InstalledApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                )
            }.sortedWith(
                compareByDescending<InstalledApp> { it.packageName in selected }
                    .thenBy { it.label.lowercase() }
            )
            runOnUiThread {
                if (!isFinishing && !isDestroyed) adapter.submit(apps)
            }
        }.start()
    }

    companion object {
        const val EXTRA_LIST = "list"
        const val LIST_VPN = "vpn"
    }
}
