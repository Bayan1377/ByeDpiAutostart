package ru.dsh.byediautostart

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Environment
import android.view.View

/** Обои рабочего стола как фон приложения (под полупрозрачными карточками). */
object WallpaperBackground {

    fun apply(activity: Activity, root: View) {
        val prefs = Prefs(activity)
        if (!prefs.wallpaperBackground || !Environment.isExternalStorageManager()) {
            root.setBackgroundResource(R.drawable.bg_screen)
            return
        }
        try {
            val wallpaper = WallpaperManager.getInstance(activity).drawable
            if (wallpaper == null) {
                root.setBackgroundResource(R.drawable.bg_screen)
                return
            }
            val scrim = ColorDrawable(
                if (prefs.darkTheme) Color.argb(80, 0, 0, 0) else Color.argb(50, 255, 255, 255),
            )
            root.background = LayerDrawable(arrayOf(wallpaper, scrim))
        } catch (e: Exception) {
            root.setBackgroundResource(R.drawable.bg_screen)
        }
    }
}
