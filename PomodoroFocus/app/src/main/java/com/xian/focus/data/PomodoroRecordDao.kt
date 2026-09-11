package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PomodoroRecordDao {
    @Insert
    suspend fun insertRecord(record: PomodoroRecord): Long

    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_records
        WHERE type='focus' AND isFinished=1
          AND startTime>=:startTime AND startTime<:endTime
        """
    )
    suspend fun getCompletedFocusCountBetween(startTime: Long, endTime: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM pomodoro_records
        WHERE type='focus' AND isFinished=1
        """
    )
    suspend fun getTotalCompletedFocusCount(): Int

    @Query(
        """
        SELECT COALESCE(SUM((endTime - startTime) / 60000), 0)
        FROM pomodoro_records
        WHERE type='focus' AND isFinished=1
        """
    )
    suspend fun getTotalFocusMinutes(): Int

    @Query(
        """
        SELECT COALESCE(SUM((endTime - startTime) / 60000), 0)
        FROM pomodoro_records
        WHERE type='focus' AND isFinished=1
          AND startTime>=:startTime AND startTime<:endTime
        """
    )
    suspend fun getFocusMinutesBetween(startTime: Long, endTime: Long): Int

    @Query("SELECT * FROM pomodoro_records ORDER BY startTime DESC")
    suspend fun getAllRecords(): List<PomodoroRecord>

    @Query(
        """
        SELECT startTime FROM pomodoro_records
        WHERE type='focus' AND isFinished=1
          AND startTime>=:startTime AND startTime<:endTime
        """
    )
    suspend fun getFocusStartTimesBetween(startTime: Long, endTime: Long): List<Long>
}
