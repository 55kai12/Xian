package com.xian.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            LockMachineScheduler.ACTION_START -> {
                val durationMinutes = slotDurationMinutes(context, intent)
                if (durationMinutes > 0) {
                    LockMachineService.start(context, durationMinutes)
                }
                // 重新排下一次闹钟。
                // setAlarmClock 是一次性闹钟，触发后不会自动重复；不重排的话
                // 定时锁机只在设置后生效一次，之后（除非重启或重新保存）再也不锁。
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
     * 本次该锁多久。多时段下每个「开始」闹钟都自带自己那个时段的起止分钟
     * （Intent 的 extras 按 requestCode 区分），所以这里优先用闹钟自己带的参数；
     * 早期版本排的闹钟没带参数，才退回「此刻正在生效的时段」。
     */
    private fun slotDurationMinutes(context: Context, intent: Intent): Int {
        val start = intent.getIntExtra(LockMachineScheduler.EXTRA_SLOT_START, -1)
        val end = intent.getIntExtra(LockMachineScheduler.EXTRA_SLOT_END, -1)
        if (start >= 0 && end >= 0) {
            val slot = LockMachineScheduler.Slot(start, end)
            if (slot.valid) return slot.durationMinutes
        }
        return LockMachineScheduler.currentSlot(context)?.durationMinutes ?: 0
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
