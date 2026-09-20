package com.xian.focus

import android.content.Context
import com.xian.focus.data.Countdown
import com.xian.focus.data.CountdownCalendar
import java.util.Calendar

/**
 * 倒数日提醒：驱动「事件设置 → 事件到期提醒」这个开关。
 *
 * 开关此前只把 reminder_enabled / reminder_days 写进 SharedPreferences，
 * 没有任何代码读它 —— 用户打开开关、设置提前天数，什么也不会发生。
 * 这里把它接上：目标日往前 N 天的 09:00 发一条通知。
 */
object CountdownReminderScheduler {

    private const val PREFS_NAME = "event_settings"
    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_DAYS = "reminder_days"

    /** 提醒时刻：目标日往前 reminderDays 天的 09:00。 */
    private const val REMIND_HOUR = 9

    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** requestCode 与任务 id 分处两个区间，避免撞车（任务 id 从 1 自增，短期到不了这里）。 */
    private const val REQUEST_BASE = 500_000
    private const val NOTIFICATION_BASE = 3000

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun reminderDays(context: Context): Int = prefs(context).getInt(KEY_DAYS, 1)

    /** 事件新增 / 修改 / 删除后调用；开关关闭或提醒点已过会自动撤掉闹钟。 */
    fun sync(context: Context, countdown: Countdown) {
        if (!isEnabled(context)) {
            cancel(context, countdown.id)
            return
        }
        val triggerAt = startOfDay(effectiveTargetDate(countdown)) +
            REMIND_HOUR * 60L * 60L * 1000L -
            reminderDays(context) * DAY_MILLIS
        ReminderScheduler.schedule(
            context = context,
            requestCode = REQUEST_BASE + countdown.id,
            notificationId = NOTIFICATION_BASE + countdown.id,
            title = context.getString(R.string.reminder_countdown_title),
            text = countdown.title,
            triggerAt = triggerAt
        )
    }

    fun cancel(context: Context, countdownId: Int) {
        ReminderScheduler.cancel(context, REQUEST_BASE + countdownId)
    }

    /**
     * 倒计日的「下一次」目标日期：每年重复的取今年，今年已过就顺延一年。
     * 列表显示、剩余天数、提醒三者必须用同一口径，所以放在这里由各方共用。
     *
     * 农历条目恒定按年重复 —— 用户记的是「农历八月十五」，每年的公历位置自己会挪，
     * 所以这里不看 repeatYearly，一律走农历换算（保存时会把 repeatYearly 置真，
     * 好让列表后缀、关联任务那些既有判断不用为农历再开岔路）。
     */
    fun effectiveTargetDate(countdown: Countdown, now: Long = System.currentTimeMillis()): Long {
        if (countdown.calendarType == CountdownCalendar.LUNAR) {
            return LunarCalendar.nextOccurrence(
                countdown.lunarMonth,
                countdown.lunarDay,
                countdown.lunarLeap,
                now
            )
        }
        if (!countdown.repeatYearly) return countdown.targetDate
        val target = Calendar.getInstance().apply { timeInMillis = countdown.targetDate }
        target.set(Calendar.YEAR, Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR))
        if (target.timeInMillis < now) target.add(Calendar.YEAR, 1)
        return target.timeInMillis
    }

    private fun startOfDay(timeMillis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
