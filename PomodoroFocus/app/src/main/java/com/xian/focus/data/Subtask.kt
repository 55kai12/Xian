package com.xian.focus.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subtasks",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId")]
)
data class Subtask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val taskId: Int,
    val title: String,
    val isCompleted: Boolean = false
)
