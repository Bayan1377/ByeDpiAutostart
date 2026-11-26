package ru.dsh.byediautostart

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

/** Применяет выбранную в настройках прозрачность ко всем карточкам и подсказкам. */
object CardStyler {

    fun apply(activity: Activity, root: View) {
        val transparency = Prefs(activity).cardTransparency
        val alpha = ((100 - transparency) * 255 / 100).coerceIn(0, 255)
        val base = ContextCompat.getColor(activity, R.color.card_bg)
        val color = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        val hintColor = color
        val stroke = ContextCompat.getColor(activity, R.color.card_stroke)
        val radius = 18 * activity.resources.displayMetrics.density
        val strokeWidth = (1 * activity.resources.displayMetrics.density).toInt()

        walk(root) { view ->
            when (view) {
                is MaterialCardView -> view.setCardBackgroundColor(color)
                else -> if (view.id == R.id.tvHint && view is TextView) {
                    view.background = GradientDrawable().apply {
                        setColor(hintColor)
                        cornerRadius = radius
                        setStroke(strokeWidth, stroke)
                    }
                }
            }
        }
    }

    private fun walk(view: View, block: (View) -> Unit) {
        block(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), block)
        }
    }
}
