package com.alienbooster.claimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts one claim run, then arms the next day's alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        ShizukuManager.init(app)
        if (!AppSettings.scheduleEnabled(app)) return
        ScheduleManager.reschedule(app)
        val start = Intent(app, ClaimService::class.java)
            .putExtra(ClaimExtras.AUTO_START, true)
        ClaimService.start(app, start)
    }
}
