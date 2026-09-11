package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SubtaskDao {
    @Insert
    suspend fun insert(subtask: Subtask): Long

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
