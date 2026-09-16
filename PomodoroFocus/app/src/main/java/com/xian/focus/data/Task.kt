package com.xian.focus.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String? = null,
    val priority: Int = 2,
    val estimatedPomodoros: Int = 1,
    val completedPomodoros: Int = 0,
    val isCompleted: Boolean = false,
    val dueDate: Long? = null,
    val dueTimeMinutes: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Long = createdAt,
    val imageUri: String? = null,
    val listType: String = "未分类",
    val repeatRule: String = "none",
    val templateId: Int = 0,
    /** 删除时间戳，0 表示没删。>0 的行只在回收站里出现，所有正常查询都要排除。 */
    val deletedAt: Long = 0
)
