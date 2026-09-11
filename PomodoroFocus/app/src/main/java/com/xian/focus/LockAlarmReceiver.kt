package com.xian.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LockMachineScheduler.ACTION_START -> {
                val start = LockMachineScheduler.startMinute(context)
                val end = LockMachineScheduler.endMinute(context)
                val durationMinutes = ((end - start) + 1440) % 1440
                if (durationMinutes > 0) {
                    LockMachineService.start(context, durationMinutes, LockMachineController.whitelist(context))
                }
            }
            LockMachineScheduler.ACTION_END -> {
                LockMachineService.stop(context)
            }
        }
    }
}
