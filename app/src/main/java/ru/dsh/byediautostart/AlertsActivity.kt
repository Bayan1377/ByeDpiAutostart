package ru.dsh.byediautostart

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import ru.dsh.byediautostart.databinding.ActivityAlertsBinding

class AlertsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertsBinding
    private val adapter = AlertsAdapter { entry ->
        if (entry.type == "accessibility") {
            openAccessibilitySettings()
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.themeRes(this))
        super.onCreate(savedInstanceState)
        binding = ActivityAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        StatusBarCard.apply(this, binding.root)
        binding.toolbar.setBackgroundResource(ThemeManager.headerRes(this))

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.alerts_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear_alerts) {
                AlertLog.clear(this)
                render()
                true
            } else {
                false
            }
        }

        binding.rvAlerts.layoutManager = LinearLayoutManager(this)
        binding.rvAlerts.adapter = adapter

        AlertLog.markAllRead(this)
        Notifications.clearProblems(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        AlertLog.markAllRead(this)
        Notifications.clearProblems(this)
        render()
    }

    private fun render() {
        val entries = AlertLog.all(this)
        adapter.submit(entries)
        binding.tvEmpty.isVisible = entries.isEmpty()
        binding.rvAlerts.isVisible = entries.isNotEmpty()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
