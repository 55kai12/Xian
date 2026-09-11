package com.xian.focus.data

import android.content.Context
import android.content.SharedPreferences

data class TimerDurations(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15
) {
    val focusSeconds: Int get() = focusMinutes * 60
    val shortBreakSeconds: Int get() = shortBreakMinutes * 60
    val longBreakSeconds: Int get() = longBreakMinutes * 60
}

data class TimerSession(
    val status: String,
    val pausedFromStatus: String?,
    val remainingSeconds: Int,
    val taskId: Int?,
    val completedPomodoros: Int,
    val focusStartedAt: Long,
    /**
     * 当前阶段（专注/短休/长休）应当结束的墙钟时间戳。
     * 用于按真实时间推算剩余秒数，而不是靠"每秒减一"累加 —— 息屏 / Doze
     * 会让协程的 delay 被拉长，导致 25 分钟的番茄钟实际跑成半小时以上。
     * PAUSED 状态或旧版本写入的 session 为 0。
     */
    val phaseEndAt: Long = 0L
)

class TimerSettingsPreferences(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "timer_settings",
        Context.MODE_PRIVATE
)

    fun getDurations(): TimerDurations = TimerDurations(
        focusMinutes = preferences.getInt(KEY_FOCUS_MINUTES, 25).coerceIn(MIN_MINUTES, MAX_FOCUS_MINUTES),
        shortBreakMinutes = preferences.getInt(KEY_SHORT_BREAK_MINUTES, 5).coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES),
        longBreakMinutes = preferences.getInt(KEY_LONG_BREAK_MINUTES, 15).coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES)
    )

    fun saveDurations(durations: TimerDurations): TimerDurations {
        val normalized = TimerDurations(
            focusMinutes = durations.focusMinutes.coerceIn(MIN_MINUTES, MAX_FOCUS_MINUTES),
            shortBreakMinutes = durations.shortBreakMinutes.coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES),
            longBreakMinutes = durations.longBreakMinutes.coerceIn(MIN_MINUTES, MAX_BREAK_MINUTES)
        )
        preferences.edit()
            .putInt(KEY_FOCUS_MINUTES, normalized.focusMinutes)
            .putInt(KEY_SHORT_BREAK_MINUTES, normalized.shortBreakMinutes)
            .putInt(KEY_LONG_BREAK_MINUTES, normalized.longBreakMinutes)
            .apply()
        return normalized
    }

    fun getSession(): TimerSession? {
        val status = preferences.getString(KEY_SESSION_STATUS, null) ?: return null
        return TimerSession(
            status = status,
            pausedFromStatus = preferences.getString(KEY_SESSION_PAUSED_FROM, null),
            remainingSeconds = preferences.getInt(KEY_SESSION_REMAINING, 0),
            taskId = preferences.getInt(KEY_SESSION_TASK_ID, -1).takeIf { it >= 0 },
            completedPomodoros = preferences.getInt(KEY_SESSION_COMPLETED, 0),
            focusStartedAt = preferences.getLong(KEY_SESSION_STARTED_AT, 0L),
            phaseEndAt = preferences.getLong(KEY_SESSION_PHASE_END_AT, 0L)
        )
    }

    fun saveSession(session: TimerSession) {
        preferences.edit()
            .putString(KEY_SESSION_STATUS, session.status)
            .putString(KEY_SESSION_PAUSED_FROM, session.pausedFromStatus)
            .putInt(KEY_SESSION_REMAINING, session.remainingSeconds)
            .putInt(KEY_SESSION_TASK_ID, session.taskId ?: -1)
            .putInt(KEY_SESSION_COMPLETED, session.completedPomodoros)
            .putLong(KEY_SESSION_STARTED_AT, session.focusStartedAt)
            .putLong(KEY_SESSION_PHASE_END_AT, session.phaseEndAt)
            .apply()
    }

    fun clearSession() {
        preferences.edit()
            .remove(KEY_SESSION_STATUS)
            .remove(KEY_SESSION_PAUSED_FROM)
            .remove(KEY_SESSION_REMAINING)
            .remove(KEY_SESSION_TASK_ID)
            .remove(KEY_SESSION_COMPLETED)
            .remove(KEY_SESSION_STARTED_AT)
            .remove(KEY_SESSION_PHASE_END_AT)
            .apply()
    }

    /**
     * 用户是否开启了「锁机专注」。
     * 必须持久化：旧实现只存在内存静态变量里，进程被系统回收后重新拉起时
     * 会变回 false，锁机界面静默消失 —— 这是锁机类应用最忌讳的绕过口。
     */
    fun isLockEnabled(): Boolean = preferences.getBoolean(KEY_LOCK_ENABLED, false)

    fun saveLockEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_FOCUS_MINUTES = "focus_minutes"
        const val KEY_SHORT_BREAK_MINUTES = "short_break_minutes"
        const val KEY_LONG_BREAK_MINUTES = "long_break_minutes"
        const val MIN_MINUTES = 1
        const val MAX_FOCUS_MINUTES = 480
        const val MAX_BREAK_MINUTES = 60
        const val KEY_SESSION_STATUS = "session_status"
        const val KEY_SESSION_PAUSED_FROM = "session_paused_from"
        const val KEY_SESSION_REMAINING = "session_remaining"
        const val KEY_SESSION_TASK_ID = "session_task_id"
        const val KEY_SESSION_COMPLETED = "session_completed"
        const val KEY_SESSION_STARTED_AT = "session_started_at"
        const val KEY_SESSION_PHASE_END_AT = "session_phase_end_at"
        const val KEY_LOCK_ENABLED = "lock_enabled"
    }
}
