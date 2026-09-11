package com.xian.focus

import android.content.Context
import com.xian.focus.data.Task

/**
 * 任务到期提醒：按「截止日 + 截止时间」排一次性闹钟。
 * requestCode 直接取任务 id，所以「改期」就是原地覆盖，天然幂等。
 */
object TaskReminderScheduler {

    /** 任务没设具体时刻时的提醒点：与任务列表排序口径一致（当天 09:00）。 */
    private const val DEFAULT_MINUTE_OF_DAY = 9 * 60

    /** 通知 id 与倒数日那片错开。 */
    private const val NOTIFICATION_BASE = 2000

    fun schedule(context: Context, task: Task) {
        val triggerAt = triggerAt(task)
        // 已完成、没有截止日期、或提醒点已经过去 —— 一律撤掉，不补发。
        if (task.isCompleted || triggerAt == null) {
            cancel(context, task.id)
            return
        }
        ReminderScheduler.schedule(
            context = context,
            requestCode = task.id,
            notificationId = NOTIFICATION_BASE + task.id,
            title = context.getString(R.string.reminder_task_title),
            text = task.title,
            triggerAt = triggerAt
        )
    }

    fun cancel(context: Context, taskId: Int) {
        ReminderScheduler.cancel(context, taskId)
    }

    /** dueDate 存的是截止日当天 0 点，截止时间是「当天第几分钟」。 */
    private fun triggerAt(task: Task): Long? {
        val dueDate = task.dueDate ?: return null
        return dueDate + (task.dueTimeMinutes ?: DEFAULT_MINUTE_OF_DAY) * 60_000L
    }
}
