package ru.dsh.byediautostart

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Промежуточный экран для перехода из уведомления в настройки специальных возможностей. */
class AccessibilityOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        finish()
    }
}
