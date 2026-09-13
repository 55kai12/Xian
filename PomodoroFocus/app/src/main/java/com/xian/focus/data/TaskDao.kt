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

    /** 「删除全部该事件」：模板行连同它名下所有快照行一起删掉。 */
    @Query("DELETE FROM tasks WHERE id=:templateId OR templateId=:templateId")
    suspend fun deleteTemplateWithSnapshots(templateId: Int)

    /**
     * 「删除全部该事件（保留已完成的）」第一步：把已完成的快照摘出来变成普通任务。
     *
     * 必须连 repeatRule 一起清掉 —— 只把 templateId 置 0 的话，
     * `repeatRule != none && templateId == 0` 恰好就是「重复任务模板」的判定条件，
     * 这些本该安静留在历史里的记录会被当成模板，从今天起每天重新冒出来。
     */
    @Query("UPDATE tasks SET templateId=0, repeatRule='none' WHERE templateId=:templateId AND isCompleted=1")
    suspend fun detachCompletedSnapshots(templateId: Int)

    /** 上一步之后的第二步：删掉模板本身，以及仍属该系列的未完成快照。 */
    @Query("DELETE FROM tasks WHERE id=:templateId OR (templateId=:templateId AND isCompleted=0)")
    suspend fun deleteTemplateAndUnfinishedSnapshots(templateId: Int)
}
