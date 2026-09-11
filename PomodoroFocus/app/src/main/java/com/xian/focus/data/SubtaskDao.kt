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

    @Query("SELECT * FROM subtasks WHERE taskId=:taskId ORDER BY id ASC")
    suspend fun getByTaskId(taskId: Int): List<Subtask>

    @Query("SELECT * FROM subtasks ORDER BY id ASC")
    suspend fun getAll(): List<Subtask>
}
