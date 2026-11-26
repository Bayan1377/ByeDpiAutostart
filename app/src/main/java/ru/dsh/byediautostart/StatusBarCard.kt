package ru.dsh.byediautostart

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Рисует полупрозрачную карточку под системным статус-баром (время, батарея, значки)
 * и включает edge-to-edge, чтобы она была видна за иконками.
 */
object StatusBarCard {

    fun apply(activity: Activity, root: ViewGroup) {
        if (root is LinearLayout && root.getTag(R.id.status_bar_card_tag) == true) return

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(activity.window, root).isAppearanceLightStatusBars =
            !Prefs(activity).darkTheme

        val prefs = Prefs(activity)
        val transparency = prefs.cardTransparency
        val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        val base = ContextCompat.getColor(activity, R.color.card_bg)
        val stroke = ContextCompat.getColor(activity, R.color.card_stroke)
        val density = activity.resources.displayMetrics.density
        val card = View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * density
                setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)))
                setStroke((1 * density).toInt(), stroke)
            }
        }

        val statusHeight = statusBarHeight(activity)
        if (root is LinearLayout) {
            root.addView(
                card,
                0,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    statusHeight,
                ).apply { setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0) },
            )
            root.setTag(R.id.status_bar_card_tag, true)
        }
    }

    private fun statusBarHeight(activity: Activity): Int {
        val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) activity.resources.getDimensionPixelSize(id)
        else (24 * activity.resources.displayMetrics.density).toInt()
    }
}
