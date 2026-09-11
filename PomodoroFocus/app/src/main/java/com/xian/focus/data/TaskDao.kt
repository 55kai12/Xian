package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE listType=:listType ORDER BY createdAt DESC")
    suspend fun getTasksByListType(listType: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=0 ORDER BY (dueDate IS NULL), dueDate ASC, (dueTimeMinutes IS NULL), dueTimeMinutes ASC, priority ASC, createdAt DESC")
    suspend fun getPendingTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=0 AND listType=:listType ORDER BY (dueDate IS NULL), dueDate ASC, (dueTimeMinutes IS NULL), dueTimeMinutes ASC, priority ASC, createdAt DESC")
    suspend fun getPendingTasksByListType(listType: String): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=1 ORDER BY createdAt DESC")
    suspend fun getCompletedTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE isCompleted=1 AND listType=:listType ORDER BY createdAt DESC")
    suspend fun getCompletedTasksByListType(listType: String): List<Task>

    @Query("SELECT DISTINCT listType FROM tasks ORDER BY listType ASC")
    suspend fun getListTypes(): List<String>

    @Query("SELECT * FROM tasks WHERE id=:id LIMIT 1")
    suspend fun getTaskById(id: Int): Task?

    @Query("SELECT * FROM tasks WHERE templateId=:templateId AND dueDate=:dueDate AND isCompleted=1 LIMIT 1")
    suspend fun getCompletedSnapshot(templateId: Int, dueDate: Long): Task?

    @Query("DELETE FROM tasks WHERE templateId=:templateId AND dueDate=:dueDate AND isCompleted=1")
    suspend fun deleteCompletedSnapshot(templateId: Int, dueDate: Long)

    @Insert
    suspend fun insertTask(task: Task): Long

    /**
     * CSV 恢复专用：按原 id 批量写回。
     * 用 IGNORE 而不是默认的 ABORT —— 把备份导进一台已有数据的手机时，
     * 撞 id 的行应当跳过，而不是让整次导入崩掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTasksFromBackup(tasks: List<Task>)

    @Update
    suspend fun updateTask(task: Task)

    @Update
    suspend fun updateTasks(tasks: List<Task>)

    @Delete
    suspend fun deleteTask(task: Task)
}
