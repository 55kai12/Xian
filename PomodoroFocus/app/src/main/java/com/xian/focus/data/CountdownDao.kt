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

    /** CSV 恢复专用：与 [insert] 不同，这里撞 id 时保留库里已有的那条。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFromBackup(countdowns: List<Countdown>)

    @Update
    suspend fun update(countdown: Countdown)

    @Delete
    suspend fun delete(countdown: Countdown)

    @Query("DELETE FROM countdowns WHERE id = :id")
    suspend fun deleteById(id: Int)
}
