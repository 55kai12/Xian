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
    val focusStartedAt: Long
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
            focusStartedAt = preferences.getLong(KEY_SESSION_STARTED_AT, 0L)
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
            .apply()
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
    }
}
