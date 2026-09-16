package com.xian.focus

import com.xian.focus.data.Habit
import com.xian.focus.data.HabitLog
import java.util.Calendar

/**
 * 习惯的「今天的次数 / 有没有达标 / 连续多少天 / 本周进度」。
 *
 * 页面、今日进度、以后的统计都问这一个地方 —— 与 [RepeatRule] 同样的理由：
 * 两份实现一旦漂移，就会出现「列表说连续 5 天、统计说 3 天」这种用户一眼看出对不上的数字。
 */
object HabitStreak {

    const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** 一个习惯此刻在界面上要显示的东西。 */
    data class Status(
        /** 今天已打次数。 */
        val todayCount: Int,
        /** 每日目标次数（至少 1）。 */
        val target: Int,
        /** 今天是否已经达标：`todayCount >= target`。 */
        val done: Boolean,
        /** 连续达标天数。**只对「每天」类有意义**，其余频率恒 0（见类注释）。 */
        val streak: Int,
        /** 本周已达标的天数。 */
        val weekDone: Int,
        /** 本周该达标的总天数：每天 = 7、每周 N 次 = N、指定星期 = 该周出现的天数。 */
        val weekTarget: Int
    )

    /**
     * 算一个习惯的状态。
     *
     * [logs] 传该习惯的**全部**日志 —— 连续天数要往回数到起始日，不能只取近期窗口，
     * 否则「连续 400 天」会被截成窗口长度。
     */
    fun status(habit: Habit, logs: List<HabitLog>, now: Long = System.currentTimeMillis()): Status {
        val today = startOfDay(now)
        // 一天一行（(habitId, day) 唯一），所以直接按天建索引
        val byDay = logs.associateBy { it.day }
        val target = habit.targetPerDay.coerceAtLeast(1)
        val start = startOfDay(habit.startDate)

        val todayCount = byDay[today]?.count ?: 0

        val weekDays = (0..6).map { startOfWeek(today) + it * DAY_MILLIS }
        val weekDone = weekDays.count { day ->
            day >= start && habit.coversDay(day) && (byDay[day]?.count ?: 0) >= target
        }
        val weekTarget = when (habit.freqType) {
            Habit.FREQ_WEEKLY -> habit.weeklyTarget.coerceAtLeast(1)
            Habit.FREQ_CUSTOM -> weekDays.count { habit.coversDay(it) }
            else -> 7
        }

        return Status(
            todayCount = todayCount,
            target = target,
            done = todayCount >= target,
            streak = if (habit.freqType == Habit.FREQ_DAILY) {
                dailyStreak(byDay, today, start, target)
            } else {
                0
            },
            weekDone = weekDone,
            weekTarget = weekTarget
        )
    }

    /**
     * 「每天」类习惯的连续达标天数。
     *
     * 两条容易算错的规则：
     * - **今天还没达标不算断**：一天还没过完，从昨天开始数。不然每天早上一打开就是「连续 0 天」。
     * - **起始日之前的日期不参与**：否则新建的习惯会立刻显示成「断了很久」。
     */
    private fun dailyStreak(
        byDay: Map<Long, HabitLog>,
        today: Long,
        start: Long,
        target: Int
    ): Int {
        var cursor = if ((byDay[today]?.count ?: 0) >= target) today else today - DAY_MILLIS
        var count = 0
        while (cursor >= start && (byDay[cursor]?.count ?: 0) >= target) {
            count++
            cursor -= DAY_MILLIS
        }
        return count
    }

    /** 一周从**周一**开始 —— 与任务清单的日历、统计页同一口径。 */
    fun startOfWeek(timeMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = startOfDay(timeMillis) }
        val offset = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        return startOfDay(cal.timeInMillis - offset * DAY_MILLIS)
    }

    fun startOfDay(timeMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
