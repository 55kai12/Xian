package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SubtaskDao {
    @Insert
    suspend fun insert(subtask: Subtask): Long

    /** CSV 恢复专用，撞 id 的行跳过。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFromBackup(subtasks: List<Subtask>)

    @Update
    suspend fun update(subtask: Subtask)

    @Query("DELETE FROM subtasks WHERE id=:id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM subtasks WHERE taskId=:taskId")
    suspend fun deleteByTaskId(taskId: Int)

    /** 只删「不分日期的模板行」—— 重复任务改标题时用，别把各天的勾选记录一起删了。 */
    @Query("DELETE FROM subtasks WHERE taskId=:taskId AND dueDate IS NULL")
    suspend fun deleteTemplatesByTaskId(taskId: Int)

    @Query("SELECT * FROM subtasks WHERE taskId=:taskId ORDER BY id ASC")
    suspend fun getByTaskId(taskId: Int): List<Subtask>

    /** 某个任务在**某一天**的子任务：模板行 + 那天的具体行（后者覆盖前者）。 */
    @Query("SELECT * FROM subtasks WHERE taskId=:taskId AND (dueDate IS NULL OR dueDate=:day) ORDER BY id ASC")
    suspend fun getByTaskIdOn(taskId: Int, day: Long): List<Subtask>

    /** 某任务在某天的**具体**子任务行（不含模板行）—— 回写勾选状态时找它。 */
    @Query("SELECT * FROM subtasks WHERE taskId=:taskId AND dueDate=:day")
    suspend fun getDated(taskId: Int, day: Long): List<Subtask>

    @Query("DELETE FROM subtasks WHERE taskId=:taskId AND dueDate=:day")
    suspend fun deleteDated(taskId: Int, day: Long)

    @Query("SELECT * FROM subtasks ORDER BY id ASC")
    suspend fun getAll(): List<Subtask>
}
