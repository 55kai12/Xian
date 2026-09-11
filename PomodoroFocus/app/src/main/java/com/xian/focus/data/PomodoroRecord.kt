package com.xian.focus.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "pomodoro_records")
data class PomodoroRecord(@PrimaryKey(autoGenerate = true) val id: Int = 0, val taskId: Int? = null, val startTime: Long, val endTime: Long, val type: String, val isFinished: Boolean)
