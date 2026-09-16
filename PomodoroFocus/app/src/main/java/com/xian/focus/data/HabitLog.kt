package com.xian.focus.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一天的打卡记录。
 *
 * **一天一行**，用 [count] 攒次数；不是「打一次插一行」。
 * 后者一天能插几十行，列表要 `GROUP BY` 才看得出今天打了没，导出也变成一堆重复行。
 * 一条记录就是一天的答案。
 *
 * `(habitId, day)` 唯一索引：同一天重复写入在数据库层就挡住，不靠界面自律。
 */
@Entity(
    tableName = "habit_logs",
    indices = [Index(value = ["habitId", "day"], unique = true)]
)
data class HabitLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val habitId: Int,
    /** 打卡日（当天 0 点）。 */
    val day: Long,
    /** 当天累计次数。减到 0 时直接把这一行删掉，不留 count = 0 的空记录。 */
    val count: Int = 1,
    /** 首次打卡时刻。 */
    val createdAt: Long = System.currentTimeMillis()
)
