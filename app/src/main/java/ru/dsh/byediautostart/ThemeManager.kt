package ru.dsh.byediautostart

import android.content.Context
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/** Светлая/тёмная тема и выбранная палитра акцента. */
object ThemeManager {

    fun apply(prefs: Prefs) {
        val mode = if (prefs.darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun toggle(activity: AppCompatActivity, prefs: Prefs) {
        prefs.darkTheme = !prefs.darkTheme
        apply(prefs)
        activity.recreate()
    }

    @StyleRes
    fun themeRes(context: Context): Int = when (Prefs(context).palette) {
        "blue" -> R.style.Theme_ByeDpiAutostart_Blue
        "violet" -> R.style.Theme_ByeDpiAutostart_Violet
        "emerald" -> R.style.Theme_ByeDpiAutostart_Emerald
        "amber" -> R.style.Theme_ByeDpiAutostart_Amber
        "graphite" -> R.style.Theme_ByeDpiAutostart_Graphite
        else -> R.style.Theme_ByeDpiAutostart_Teal
    }

    fun headerRes(context: Context): Int = when (Prefs(context).palette) {
        "blue" -> R.drawable.bg_header_blue
        "violet" -> R.drawable.bg_header_violet
        "emerald" -> R.drawable.bg_header_emerald
        "amber" -> R.drawable.bg_header_amber
        "graphite" -> R.drawable.bg_header_graphite
        else -> R.drawable.bg_header_teal
    }

    fun accentColor(context: Context): Int = ContextCompat.getColor(
        context,
        when (Prefs(context).palette) {
            "blue" -> R.color.palette_blue
            "violet" -> R.color.palette_violet
            "emerald" -> R.color.palette_emerald
            "amber" -> R.color.palette_amber
            "graphite" -> R.color.palette_graphite
            else -> R.color.palette_teal
        },
    )
}

