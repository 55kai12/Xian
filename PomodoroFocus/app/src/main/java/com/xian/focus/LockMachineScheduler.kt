package com.xian.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object LockMachineScheduler {
    private const val PREFS_NAME = "lock_schedule_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_START = "start_minute"
    private const val KEY_END = "end_minute"

    const val ACTION_START = "com.xian.focus.action.LOCK_START"
    const val ACTION_END = "com.xian.focus.action.LOCK_END"

    private const val REQ_START = 1001
    private const val REQ_END = 1002

    /** 重排闹钟时对"当前时刻"留出的安全余量，避免同一秒内反复触发。 */
    private const val RESCHEDULE_GUARD_MILLIS = 1_000L

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun startMinute(context: Context): Int = prefs(context).getInt(KEY_START, 0)

    fun endMinute(context: Context): Int = prefs(context).getInt(KEY_END, 0)

    fun schedule(context: Context, startMinute: Int, endMinute: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putInt(KEY_START, startMinute)
            .putInt(KEY_END, endMinute)
            .apply()
        applyAlarms(context)
    }

    fun cancel(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        val am = alarmManager(context)
        am.cancel(startPendingIntent(context))
        am.cancel(endPendingIntent(context))
    }

    fun applyAlarms(context: Context) {
        if (!isEnabled(context)) return
        val startMinute = startMinute(context)
        val endMinute = endMinute(context)
        val now = System.currentTimeMillis()
        val startCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startMinute / 60)
            set(Calendar.MINUTE, startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // 早于（或几乎等于）当前时刻就顺延到明天。
            // 留 1 秒余量：闹钟触发后立即重排时，若刚好落在同一秒内会被判定为"还没到"，
            // 从而导致闹钟原地重排、立刻再次触发，形成死循环。
            if (timeInMillis <= now + RESCHEDULE_GUARD_MILLIS) add(Calendar.DAY_OF_MONTH, 1)
        }
        val durationMinutes = ((endMinute - startMinute) + 1440) % 1440
        val endTime = startCal.timeInMillis + durationMinutes * 60_000L
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val am = alarmManager(context)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(startCal.timeInMillis, showIntent), startPendingIntent(context))
        am.setAlarmClock(AlarmManager.AlarmClockInfo(endTime, showIntent), endPendingIntent(context))
    }

    fun resumeIfInScheduledWindow(context: Context) {
        if (!isEnabled(context) || LockMachineController.isActive(context)) return
        val start = startMinute(context)
        val end = endMinute(context)
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val duration = ((end - start) + 1440) % 1440
        if (duration == 0) return
        val elapsed = ((current - start) + 1440) % 1440
        if (elapsed < duration) {
            LockMachineService.start(context, duration - elapsed, LockMachineController.whitelist(context))
        }
    }

    private fun startPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_START,
            Intent(context, LockAlarmReceiver::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun endPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_END,
            Intent(context, LockAlarmReceiver::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
