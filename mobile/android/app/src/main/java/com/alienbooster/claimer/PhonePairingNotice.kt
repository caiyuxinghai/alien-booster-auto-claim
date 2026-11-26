package com.alienbooster.claimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

internal object PhonePairingNotice {
    private const val channel = "phone_pairing"
    private const val notice = 9301
    private const val timeoutMs = 180_000L
    internal const val REPLY = "code"
    internal const val SESSION = "pairing_session"
    private var current: String? = null

    fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(channel, "手机配对", NotificationManager.IMPORTANCE_HIGH))
        val session = UUID.randomUUID().toString()
        current = session
        val intent = Intent(context, PhonePairingReplyReceiver::class.java).putExtra(SESSION, session)
        val pending = PendingIntent.getBroadcast(context, notice, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder(REPLY).setLabel("六位配对码").build()
        val reply = Notification.Action.Builder(android.R.drawable.ic_menu_edit, "输入配对码", pending)
            .addRemoteInput(input).build()
        val notification = builder(context, "手机无线配对", "打开系统配对码窗口后，在这里填写六位码")
            .addAction(reply).setTimeoutAfter(timeoutMs).build()
        manager.notify(notice, notification)
    }

    fun receive(context: Context, session: String?, code: String): Boolean {
        if (session == null || session != current) return false
        current = null
        if (!code.matches(Regex("[0-9]{6}"))) {
            status(context, "配对码需要是六位数字，请从 App 重新发起配对")
            return false
        }
        status(context, "正在连接本机无线调试")
        return true
    }

    fun status(context: Context, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notice, builder(context, "手机配对", message).setTimeoutAfter(30_000).build())
    }

    private fun builder(context: Context, title: String, message: String): Notification.Builder {
        val open = PendingIntent.getActivity(context, notice,
            Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, channel).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(message).setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
    }
}

class PhonePairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PhonePairingNotice.REPLY)
            ?.toString()?.trim().orEmpty()
        if (!PhonePairingNotice.receive(context, intent.getStringExtra(PhonePairingNotice.SESSION), code)) return
        val pending = goAsync()
        val app = context.applicationContext
        PhoneHelperBootstrap.pair(app, code) { result ->
            PhonePairingNotice.status(app, result.fold(
                { "配对成功，回 App 点「启动助手」" },
                { it.message ?: "配对失败，请重新打开系统配对窗口" }
            ))
            pending.finish()
        }
    }
}
