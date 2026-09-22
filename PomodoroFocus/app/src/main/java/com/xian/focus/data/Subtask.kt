package com.xian.focus.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 子任务。
 *
 * [dueDate] 是 v2.0.94 加的**日期维度**，语义分两种：
 *
 * 1. **重复任务（每天 / 每周…）**：`dueDate` 是子任务「属于哪一天」的零点毫秒值。
 *    重复任务每天生成一个虚拟实例，而子任务原先只挂在模板的 taskId 上 ——
 *    所有日期共用同一批行、共用同一个 `isCompleted`，于是「昨天勾了，今天也跟着勾上」。
 *    现在每天的勾选各存各的行，互不影响。
 * 2. **`dueDate == null` / 普通任务**：就是老行为，一行一子任务、完成状态跟着任务走。
 *
 * ⚠️ **押表尾加列**：`dueDate` 必须排在 `isCompleted` 之后。导入端是按列位置读的
 * （见 `DataRestore.parseSheetSubtask`），插在中间会让旧备份整列错位且不报错。
 * 老数据这一列全为 null，行为与 v2.0.93 完全一致。
 */
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
    val isCompleted: Boolean = false,
    /** 所属日期的零点毫秒；null = 不分日期（普通任务 / 重复任务的标题模板行）。 */
    val dueDate: Long? = null
)
