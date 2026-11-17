package com.alienbooster.claimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the daily alarm. Asks the user to start Shizuku when it is down. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        ShizukuManager.init(app)
        ScheduleManager.reschedule(app)
        if (!ShizukuManager.ping()) {
            NotificationHelper.reboot(app)
        }
    }
}
