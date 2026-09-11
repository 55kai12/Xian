package com.xian.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        LockMachineService.resume(context)
        LockMachineScheduler.applyAlarms(context)
        LockMachineScheduler.resumeIfInScheduledWindow(context)
        rescheduleReminders(context)
    }

    /**
     * 重启后 AlarmManager 里的提醒全部丢失 —— 只补锁机闹钟的话，
     * 用户的截止提醒会静默失效到下一条任务被保存为止。
     * 查库是挂起操作，用 goAsync() 把广播的存活期延长到写完为止。
     */
    private fun rescheduleReminders(context: Context) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val repository = EntryPointAccessors
            .fromApplication(appContext, LockEntryPoint::class.java)
            .focusRepository()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.getAllTasks()
                    .filter { it.repeatRule == TaskViewModel.REPEAT_NONE }
                    .forEach { TaskReminderScheduler.schedule(appContext, it) }
                repository.getAllCountdownsOnce()
                    .forEach { CountdownReminderScheduler.sync(appContext, it) }
            } catch (e: Exception) {
                // 重建失败不该让开机流程崩掉，下次进应用时会重新排。
            } finally {
                pending.finish()
            }
        }
    }
}
