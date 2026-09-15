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
                // 时段结束是**自然到期**，不是用户主动退出 —— 所以绝不能走 stop()：
                // 那条路的入口会先 `LockExitQuota.consume()` 扣一次月度退出额度，
                // 还把这笔算成「主动退出」的统计。
                //
                // 闹钟的结束时刻来自 applyAlarms 排的那一刻，而真正生效的结束时刻
                // 是服务启动时自己写的（now + 时长，通常比闹钟晚零点几秒），
                // 所以这里触发时 isActive 往往还是 true —— 每次都实打实地扣。
                // 额度只有 2 次/月：设一个每天晚上的时段，两天就被扣光，
                // 之后用户想主动退出锁机也没额度了，看起来就是「卡在锁机里出不去」。
                //
                // 收尾本来也不需要这里做：时间一到，服务自己那个每秒循环的
                // isActive 变 false 就会退出循环、记一次「忍住了」、清状态并撤层。
                // 这里只负责把下一个周期的闹钟排上。
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
