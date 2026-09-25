package com.alienbooster.claimer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Schedule.arm(context)
        if (Schedule.shouldCatchUp(context)) AdClaimService.requestRun()
    }
}
