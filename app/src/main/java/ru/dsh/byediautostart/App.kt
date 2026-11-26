package ru.dsh.byediautostart

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        prefs.migrateTargets()
        ThemeManager.apply(prefs)
        Notifications.ensureChannels(this)
        AutomationController.attach(this)
        UpdateChecker.init(this)
    }
}
