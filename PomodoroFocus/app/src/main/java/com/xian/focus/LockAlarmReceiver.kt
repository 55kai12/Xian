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
                // 重新排下一次闹钟。
                // 旧实现漏了这一步：setAlarmClock 是一次性闹钟，触发后不会自动重复，
                // 于是「自定义锁机时间段」只在设置后的第一天生效，第二天起再也不锁
                // （除非用户重启手机或重新进设置页保存一次）。
                rescheduleNext(context)
            }
            LockMachineScheduler.ACTION_END -> {
                LockMachineService.stop(context)
                // 结束闹钟同样是一次性的，必须重排，否则第二天不会开始、也不会结束。
                rescheduleNext(context)
            }
        }
    }

    /**
     * 在闹钟触发后立刻重排，保证周期滚动。
     * applyAlarms() 内部会跳过已过去的时间点并把日期推到明天，
     * 因此刚刚触发过的时刻不会被重复触发。
     */
    private fun rescheduleNext(context: Context) {
        if (!LockMachineScheduler.isEnabled(context)) return
        runCatching { LockMachineScheduler.applyAlarms(context) }
    }
}
