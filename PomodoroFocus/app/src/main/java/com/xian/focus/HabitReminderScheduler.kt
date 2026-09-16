package com.xian.focus

import android.content.Context
import com.xian.focus.data.Habit
import java.util.Calendar

/**
 * 习惯提醒：每个习惯一个固定时刻，每天响一次。
 *
 * 与任务到期、倒数日提前提醒共用 [ReminderScheduler] 这条管道，唯一的区别是**每天重复**：
 * 闹钟响过之后由 [ReminderReceiver] 自己算下一天并重排（纯时间运算，仍然不查数据库），
 * 所以这里只负责「排下一次」。
 *
 * 到点就提醒，**不判断今天是否已经达标**：那要读数据库，与「接收器不碰数据库」的设计冲突；
 * 通知文案因此写成中性的「该打卡了」，而不是「你还没打卡」。
 */
object HabitReminderScheduler {

    /**
     * requestCode 与任务、倒数日分处三个不重叠的区间：
     * 任务 id 从 1 自增、倒数日 500000 起、习惯 700000 起 —— 撞车会让一条提醒顶掉另一条。
     */
    private const val REQUEST_BASE = 700_000
    private const val NOTIFICATION_BASE = 5000

    /** 排下一次。不提醒、或习惯已经进了回收站，就撤掉闹钟。 */
    fun sync(context: Context, habit: Habit) {
        if (habit.remindMinutes < 0 || habit.deletedAt > 0) {
            cancel(context, habit.id)
            return
        }
        ReminderScheduler.schedule(
            context = context,
            requestCode = REQUEST_BASE + habit.id,
            notificationId = NOTIFICATION_BASE + habit.id,
            title = context.getString(R.string.habit_notify_title),
            text = context.getString(R.string.habit_notify_text, habit.name),
            triggerAt = nextTrigger(habit.remindMinutes),
            repeatDaily = true
        )
    }

    fun cancel(context: Context, habitId: Int) {
        ReminderScheduler.cancel(context, REQUEST_BASE + habitId)
    }

    /**
     * 下一次触发时刻：今天的该时刻还没到就排今天，已经过了就顺延到明天。
     *
     * 这个函数只做「时刻」这一件事，开机补排和保存后重排都问它 ——
     * 两处各写一份的话，很容易一边算了「已过就顺延」另一边忘了。
     */
    fun nextTrigger(remindMinutes: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, remindMinutes / 60)
            set(Calendar.MINUTE, remindMinutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
}
