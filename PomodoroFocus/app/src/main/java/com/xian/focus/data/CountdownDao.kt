package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CountdownDao {
    @Query("SELECT * FROM countdowns ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<Countdown>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(countdown: Countdown): Long

    @Update
    suspend fun update(countdown: Countdown)

    @Delete
    suspend fun delete(countdown: Countdown)

    @Query("DELETE FROM countdowns WHERE id = :id")
    suspend fun deleteById(id: Int)
}
