package com.xian.focus.data

import android.content.Context
import android.content.SharedPreferences

class DailyGoalPreferences(context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(
        "focus_preferences", Context.MODE_PRIVATE
    )

    fun getDailyGoal(): Int = preferences.getInt("daily_pomodoro_goal", 8)

    fun setDailyGoal(goal: Int) {
        preferences.edit().putInt("daily_pomodoro_goal", goal.coerceAtLeast(1)).apply()
    }
}
