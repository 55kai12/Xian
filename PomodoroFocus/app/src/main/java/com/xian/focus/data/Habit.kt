package com.xian.focus.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

/**
 * 小习惯。
 *
 * 与「重复任务」是两套东西，刻意不共用模型：重复任务要占贤时、进专注统计，
 * 带着优先级/预计贤时/分类/截止时间；习惯只关心「今天做了几次」。
 * 硬凑成一套会让两边都别扭（详见 docs/习惯打卡方案.md）。
 *
 * 打卡记录在 [HabitLog]，不在这个表里 —— 一天一行攒起来很快，
 * 混进习惯表会把列表查询拖慢，导出时也会把习惯表撑成几万行。
 */
@Entity(tableName = "habits")
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val color: Int = DEFAULT_COLOR,
    /** 频率：见 [FREQ_DAILY] / [FREQ_WEEKLY] / [FREQ_CUSTOM]。 */
    val freqType: String = FREQ_DAILY,
    /** 「每周 N 次」的目标次数；其余两种频率恒 0。 */
    val weeklyTarget: Int = 0,
    /** 「指定星期」的位掩码，见 [weekDaysOf] / [maskOf]。 */
    val weekDaysMask: Int = 0,
    /** 每日目标次数。1 = 普通打卡（打一下就算完成）；>1 = 计数型（「喝水 8 杯」）。 */
    val targetPerDay: Int = 1,
    /** 起始日（当天 0 点）。此前的日期不算「未完成」，免得新建的习惯一上来就显示断了几天。 */
    val startDate: Long = 0,
    /** 提醒时刻，当天第几分钟；[NO_REMIND] 表示不提醒。与任务的 dueTimeMinutes 同一套表示。 */
    val remindMinutes: Int = NO_REMIND,
    val sortOrder: Int = 0,
    /** 删除时间戳，0 表示没删。>0 的行只在回收站里出现，所有正常查询都要排除。 */
    val deletedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 今天该不该打这个卡（频率是否覆盖今天）。连续天数的判定也要问它。 */
    fun coversDay(day: Long): Boolean = when (freqType) {
        FREQ_CUSTOM -> (weekDaysMask and maskOf(day)) != 0
        FREQ_WEEKLY -> true
        else -> true
    }

    companion object {
        const val FREQ_DAILY = "daily"
        const val FREQ_WEEKLY = "weekly"
        const val FREQ_CUSTOM = "custom"

        /** 不提醒。 */
        const val NO_REMIND = -1

        val DEFAULT_COLOR = 0xFF3B5B4E.toInt()

        /**
         * 某一天对应的星期位。
         *
         * 按 `Calendar.DAY_OF_WEEK` 的顺序排（周日 = bit0 … 周六 = bit6），
         * 这样掩码与 `Calendar` 的取值只差一个减一，转换看得见、不会错位。
         */
        fun maskOf(day: Long): Int {
            val cal = Calendar.getInstance().apply { timeInMillis = day }
            return 1 shl (cal.get(Calendar.DAY_OF_WEEK) - 1)
        }

        /** 掩码里所有的星期下标（0 = 周日）。界面与导出都用它，免得各写一份遍历。 */
        fun weekDaysOf(mask: Int): List<Int> = (0..6).filter { (mask shr it) and 1 == 1 }
    }
}
