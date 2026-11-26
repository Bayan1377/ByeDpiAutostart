package ru.dsh.byediautostart

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/** Действия карточки «Приложение и ByeByeDPI», доступные из настроек. */
object ToolsActions {

    fun openByeDpi(activity: Activity) {
        val prefs = Prefs(activity)
        val pkg = prefs.byeDpiPackage ?: ByeDpiClient.findPackage(activity)
        if (pkg == null) {
            ByeDpiInstaller.offer(activity)
            return
        }
        prefs.byeDpiPackage = pkg
        if (ByeDpiClient.openApp(activity, pkg)) {
            Toast.makeText(activity, R.string.toast_byedpi_opened, Toast.LENGTH_LONG).show()
        }
    }

    fun shareApk(activity: Activity) {
        val dir = activity.getExternalFilesDir("updates")
        if (dir == null) {
            Toast.makeText(activity, R.string.share_apk_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val apk = File(dir, "ByeDpiAutostart.apk")
        try {
            dir.mkdirs()
            File(activity.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share_apk_title)))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.share_apk_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun showQrShare(activity: Activity) {
        val prefs = Prefs(activity)
        val github = prefs.githubApkUrl.trim()
        val server = if (github.isEmpty()) QrShareServer(activity) else null
        val url = if (github.isNotEmpty()) github else server?.start()
        if (url == null) {
            server?.stop()
            Toast.makeText(activity, R.string.qr_no_wifi, Toast.LENGTH_LONG).show()
            return
        }
        val isGithub = github.isNotEmpty()

        val qrSize = (280 * activity.resources.displayMetrics.density).toInt()
        val image = ImageView(activity).apply {
            setImageBitmap(QrShareServer.qrBitmap(url, 720))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        val hint = TextView(activity).apply {
            text = activity.getString(if (isGithub) R.string.qr_hint_github else R.string.qr_hint, url)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
            setTextIsSelectable(true)
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 8, 32, 0)
            addView(image, LinearLayout.LayoutParams(qrSize, qrSize))
            addView(hint)
        }

        MaterialAlertDialogBuilder(activity)
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

    fun resetDefaultTargets(activity: Activity) {
        val prefs = Prefs(activity)
        prefs.targets = Prefs.DEFAULT_TARGETS
        prefs.vpnTargets = emptySet()
        AutomationController.reloadTargets()
        Toast.makeText(activity, R.string.defaults_set, Toast.LENGTH_SHORT).show()
    }
}
