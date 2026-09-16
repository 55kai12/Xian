package com.xian.focus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 习惯与打卡日志。
 *
 * **`deletedAt = 0` 是所有正常查询的硬前提** —— 删除是「打时间戳进回收站」，
 * 漏掉这个条件的读查询会把已删习惯重新放回列表（同 tasks / countdowns 的规矩）。
 * 只有 [getTrashed] 这类回收站专用查询才反过来取 `deletedAt > 0`。
 *
 * 日志**没有**删除戳：它跟着习惯走。习惯进回收站时日志原样留着，
 * 还原回来历史就还在；只有彻底删除习惯（[purge] + [purgeLogsFor]）才一起清掉。
 */
@Dao
interface HabitDao {

    // ------------------------------------------------------------------ 习惯

    @Query("SELECT * FROM habits WHERE deletedAt = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<Habit>

    /**
     * 单个习惯。排除已删的 —— 打卡、编辑、排提醒都拿它取数据，
     * 返回已删习惯等于让它们继续操作一个「不存在」的行。
     */
    @Query("SELECT * FROM habits WHERE id=:id AND deletedAt = 0 LIMIT 1")
    suspend fun getById(id: Int): Habit?

    /**
     * 撞 id 的行跳过（导入同 id 备份时不去覆盖）。
     * `id` 传 0 才是自增；导入时会带上原备份里的 id，好让日志的「习惯编号」对得上。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(habit: Habit): Long

    @Update
    suspend fun update(habit: Habit)

    /** 移入回收站。 */
    @Query("UPDATE habits SET deletedAt=:deletedAt WHERE id=:id AND deletedAt = 0")
    suspend fun trash(id: Int, deletedAt: Long)

    @Query("SELECT * FROM habits WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun getTrashed(): List<Habit>

    @Query("UPDATE habits SET deletedAt=0 WHERE id=:id")
    suspend fun restore(id: Int)

    @Query("DELETE FROM habits WHERE id=:id")
    suspend fun purge(id: Int)

    @Query("DELETE FROM habits WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun purgeExpired(cutoff: Long)

    @Query("DELETE FROM habits WHERE deletedAt > 0")
    suspend fun purgeAllTrashed()

    // ------------------------------------------------------------------ 打卡日志

    /** 全量日志，导出用。**含已删习惯的**（还原后历史还在，见类注释）。 */
    @Query("SELECT * FROM habit_logs ORDER BY habitId ASC, day ASC")
    suspend fun getAllLogs(): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE habitId=:habitId ORDER BY day ASC")
    suspend fun getLogsFor(habitId: Int): List<HabitLog>

    /** 某天起的全部日志，算连续天数与今日进度用。 */
    @Query("SELECT * FROM habit_logs WHERE day >= :fromDay ORDER BY day ASC")
    suspend fun getLogsSince(fromDay: Long): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE habitId=:habitId AND day=:day LIMIT 1")
    suspend fun findLog(habitId: Int, day: Long): HabitLog?

    /**
     * 打卡 +1。返回受影响行数：**0 表示今天还没有记录**，调用方要接着插一行。
     * 不用 `INSERT OR REPLACE`：那会换掉主键，也会把唯一的 (habitId, day) 约束绕过去。
     */
    @Query("UPDATE habit_logs SET count = count + 1 WHERE habitId=:habitId AND day=:day")
    suspend fun bumpLog(habitId: Int, day: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(log: HabitLog): Long

    /** 导入时按备份里的次数整条写回：已有记录不动（同「不覆盖已有数据」的总口径）。 */
    @Query("UPDATE habit_logs SET count=:count WHERE habitId=:habitId AND day=:day")
    suspend fun setLogCount(habitId: Int, day: Long, count: Int): Int

    /** 撤销某天的打卡：直接删行，不留 `count = 0` 的空记录。 */
    @Query("DELETE FROM habit_logs WHERE habitId=:habitId AND day=:day")
    suspend fun clearLog(habitId: Int, day: Long)

    /** 彻底删除习惯时连带清日志，否则库里会留下认不出主人的行，导出也会多出一堆孤儿。 */
    @Query("DELETE FROM habit_logs WHERE habitId=:habitId")
    suspend fun purgeLogsFor(habitId: Int)

    /** 清空回收站时，把已删习惯的日志一并清掉。 */
    @Query("DELETE FROM habit_logs WHERE habitId IN (SELECT id FROM habits WHERE deletedAt > 0)")
    suspend fun purgeLogsOfTrashed()

    /** 回收站保留期到期清理的第一步：清掉过期习惯名下的日志（必须在删习惯行之前跑）。 */
    @Query("DELETE FROM habit_logs WHERE habitId IN (SELECT id FROM habits WHERE deletedAt > 0 AND deletedAt < :cutoff)")
    suspend fun purgeLogsForExpired(cutoff: Long)
}
