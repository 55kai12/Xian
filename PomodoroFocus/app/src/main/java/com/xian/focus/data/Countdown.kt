package com.xian.focus.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "countdowns")
data class Countdown(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val targetDate: Long,
    val repeatYearly: Boolean = false,
    val color: Int = 0xFF3B5B4E.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val linkedTaskId: Int = 0,
    val note: String = ""
)
