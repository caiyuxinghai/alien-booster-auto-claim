package com.alienbooster.claimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class DailyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RUN) return
        if (!Schedule.scheduleEnabled(context)) {
            Schedule.disarm(context)
            return
        }
        if (Schedule.isDoneToday(context)) {
            Schedule.arm(context)
            return
        }
        Schedule.raiseFullScreen(context)
        AdClaimService.requestRun()
    }

    companion object {
        const val ACTION_RUN = "com.alienbooster.claimer.RUN"
    }
}
