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
        // 各家 ROM 的开机广播不止 BOOT_COMPLETED：部分国产 ROM 走「快速开机」（QUICKBOOT_POWERON）；
        // 应用被覆盖安装（MY_PACKAGE_REPLACED）同样会清空 AlarmManager 里的闹钟。
        // 少接一个，用户的锁机计划和到期提醒就会静默失效到下一次手动打开应用。
        if (intent.action !in BOOT_ACTIONS) return
        LockMachineService.resume(context)
        LockMachineScheduler.applyAlarms(context)
        LockMachineScheduler.resumeIfInScheduledWindow(context)
        LockHealthMonitor.schedule(context)
        rescheduleReminders(context)
        // 开机是最该确认「保护还在不在」的时刻：无障碍服务被 ROM 的自启动管理拦掉时，
        // 用户收不到任何提示，锁机就等于没开。这里立刻体检一次，该提醒就提醒。
        LockHealthMonitor.check(context.applicationContext)
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
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
