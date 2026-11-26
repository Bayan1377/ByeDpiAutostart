package ru.dsh.byediautostart

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {

    private const val CHANNEL_RUN = "automation"
    private const val CHANNEL_PROBLEMS = "problems"
    private const val CHANNEL_ALERTS = "alerts"

    const val ID_RUN = 1
    const val ID_VPN = 10
    const val ID_STOP = 11
    const val ID_MISSING = 12
    const val ID_NO_INTERNET = 13
    const val ID_STRATEGY = 14
    const val ID_ACCESSIBILITY = 15

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, context.getString(R.string.notif_channel_automation), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.notif_channel_problems), NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.notif_channel_alerts)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300)
                    setShowBadge(true)
                }
        )
    }

    fun running(context: Context, vpnOn: Boolean): Notification {
        val text = context.getString(
            R.string.notif_running_text,
            context.getString(if (vpnOn) R.string.vpn_short_on else R.string.vpn_short_off),
        )
        return NotificationCompat.Builder(context, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainIntent(context))
            .build()
    }

    fun alertVpn(context: Context) {
        val title = context.getString(R.string.notif_problem_title)
        val text = context.getString(R.string.notif_problem_text)
        AlertLog.add(context, "vpn", title, text)
        var builder = alertBase(context, title, text)
        byeDpiPending(context)?.let {
            builder = builder.addAction(R.drawable.ic_notification, context.getString(R.string.action_open_byedpi), it)
        }
        notify(context, ID_VPN, builder.build())
    }

    fun alertStopProblem(context: Context) {
        val title = context.getString(R.string.notif_stop_problem_title)
        val text = context.getString(R.string.notif_stop_problem_text)
        AlertLog.add(context, "stop", title, text)
        var builder = alertBase(context, title, text)
        byeDpiPending(context)?.let {
            builder = builder.addAction(R.drawable.ic_notification, context.getString(R.string.action_open_byedpi), it)
        }
        notify(context, ID_STOP, builder.build())
    }

    fun alertMissing(context: Context) {
        val title = context.getString(R.string.notif_missing_title)
        val text = context.getString(R.string.notif_missing_text)
        AlertLog.add(context, "missing", title, text)
        val builder = alertBase(context, title, text).addAction(
            R.drawable.ic_notification,
            context.getString(R.string.action_install),
            webIntent(context, "https://github.com/romanvht/ByeByeDPI/releases/latest", 31),
        )
        notify(context, ID_MISSING, builder.build())
    }

    fun alertNoInternet(context: Context) {
        val title = context.getString(R.string.notif_no_internet_title)
        val text = context.getString(R.string.notif_no_internet_text)
        AlertLog.add(context, "internet", title, text)
        val builder = alertBase(context, title, text).addAction(
            R.drawable.ic_notification,
            context.getString(R.string.action_open_wifi),
            settingsIntent(context, Settings.ACTION_WIRELESS_SETTINGS, 32),
        )
        notify(context, ID_NO_INTERNET, builder.build())
    }

    fun alertStrategy(context: Context) {
        val title = context.getString(R.string.notif_strategy_title)
        val text = context.getString(R.string.notif_strategy_text)
        AlertLog.add(context, "strategy", title, text)
        var builder = alertBase(context, title, text)
        byeDpiPending(context)?.let {
            builder = builder.addAction(R.drawable.ic_notification, context.getString(R.string.action_open_byedpi), it)
        }
        notify(context, ID_STRATEGY, builder.build())
    }

    fun alertAccessibility(context: Context) {
        val title = context.getString(R.string.notif_accessibility_title)
        val text = context.getString(R.string.notif_accessibility_text)
        AlertLog.add(context, "accessibility", title, text)
        val content = accessibilityIntent(context)
        val builder = alertBase(context, title, text)
            .setContentIntent(content)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.action_open_accessibility),
                accessibilityIntent(context),
            )
        val notification = builder.build()
        notification.contentIntent = content
        notify(context, ID_ACCESSIBILITY, notification)
    }

    fun clearProblems(context: Context) {
        listOf(
            ID_VPN,
            ID_STOP,
            ID_MISSING,
            ID_NO_INTERNET,
            ID_STRATEGY,
            ID_ACCESSIBILITY,
        ).forEach { cancel(context, it) }
    }

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (e: SecurityException) {
            // нет разрешения — нечего отменять
        }
    }

    private fun alertBase(context: Context, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(mainIntent(context))

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Пользователь не выдал разрешение на уведомления — не роняем сервис.
        }
    }

    private fun mainIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun byeDpiPending(context: Context): PendingIntent? {
        val pkg = ByeDpiClient.findPackage(context) ?: return null
        val intent = Intent()
            .setComponent(ComponentName(pkg, "$pkg.activities.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            PendingIntent.getActivity(
                context,
                20,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun settingsIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun accessibilityIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            36,
            Intent(context, AccessibilityOpenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun webIntent(context: Context, url: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
