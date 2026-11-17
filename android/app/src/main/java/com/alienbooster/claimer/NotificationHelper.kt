package com.alienbooster.claimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Channel `claim`, low importance. Tap opens [MainActivity] with no auto-start extra. */
object NotificationHelper {
    const val CHANNEL_ID = "claim"
    const val ONGOING_ID = 1001
    const val RESULT_ID = 1002
    const val REBOOT_ID = 1003

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    fun progress(context: Context, text: String): Notification {
        ensureChannel(context)
        return base(context, text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun updateProgress(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ONGOING_ID, progress(context, text))
    }

    fun result(context: Context, text: String) {
        ensureChannel(context)
        val notification = base(context, text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(RESULT_ID, notification)
    }

    fun reboot(context: Context) {
        ensureChannel(context)
        val text = context.getString(R.string.reboot_notice)
        val notification = base(context, text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(REBOOT_ID, notification)
    }

    private fun base(context: Context, text: String): Notification.Builder {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            1,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.title))
            .setContentText(text)
            .setContentIntent(pending)
    }
}
