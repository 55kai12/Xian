package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 倒数日表。删除同样是"打时间戳进回收站"，因此 [getAll] 必须排除 `deletedAt > 0`。
 */
@Dao
interface CountdownDao {
    @Query("SELECT * FROM countdowns WHERE deletedAt = 0 ORDER BY sortOrder ASC, createdAt ASC")
    fun getAll(): Flow<List<Countdown>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(countdown: Countdown): Long

    /** CSV 恢复专用：与 [insert] 不同，这里撞 id 时保留库里已有的那条。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFromBackup(countdowns: List<Countdown>)

    @Update
    suspend fun update(countdown: Countdown)

    // ------------------------------------------------------------------ 回收站

    @Query("UPDATE countdowns SET deletedAt=:deletedAt WHERE id=:id AND deletedAt = 0")
    suspend fun trash(id: Int, deletedAt: Long)

    @Query("SELECT * FROM countdowns WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun getTrashed(): List<Countdown>

    @Query("UPDATE countdowns SET deletedAt=0 WHERE id=:id")
    suspend fun restore(id: Int)

    @Query("DELETE FROM countdowns WHERE id=:id")
    suspend fun purge(id: Int)

    @Query("DELETE FROM countdowns WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: Long)

    @Query("DELETE FROM countdowns WHERE deletedAt > 0")
    suspend fun purgeAllTrashed()
}
