package com.xian.focus.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 倒数日按哪种历法记。 */
object CountdownCalendar {
    const val SOLAR = 0
    const val LUNAR = 1
}

@Entity(tableName = "countdowns")
data class Countdown(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val targetDate: Long,
    val repeatYearly: Boolean = false,
    /**
     * 历法：[CountdownCalendar.SOLAR] 直接看 [targetDate]，
     * [CountdownCalendar.LUNAR] 改看 [lunarMonth] / [lunarDay] / [lunarLeap]。
     *
     * 农历条目**不走** [repeatYearly] 的分支：农历月日本身就是「每年这天」，
     * 存成一次性日期毫无意义（明年的公历位置会挪）。保存时会把 repeatYearly 一并置真，
     * 让列表后缀、关联任务那些既有判断不用为农历再开一条岔路。
     */
    val calendarType: Int = CountdownCalendar.SOLAR,
    /** 农历月 1..12，仅 [calendarType] = 农历时有意义。 */
    val lunarMonth: Int = 1,
    /** 农历日 1..30，仅 [calendarType] = 农历时有意义。 */
    val lunarDay: Int = 1,
    /** 是否闰月；碰上没有该闰月的年份时按普通月份算（见 LunarCalendar.lunarDayOffset）。 */
    val lunarLeap: Boolean = false,
    val color: Int = 0xFF3B5B4E.toInt(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val linkedTaskId: Int = 0,
    val note: String = "",
    /** 删除时间戳，0 表示没删。>0 的行只在回收站里出现。 */
    val deletedAt: Long = 0
)
