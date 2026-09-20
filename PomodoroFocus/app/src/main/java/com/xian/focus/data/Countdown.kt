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
     * 农历条目**也走** [repeatYearly]（v2.0.91 起）：
     * - `repeatYearly = true` → 每年的农历这一天，公历位置逐年自己挪（见 [LunarCalendar.nextOccurrence]）。
     * - `repeatYearly = false` → 只算一次，落点就是保存时换算出的 [targetDate]。
     *   农历记的是月日、本身不含年份，所以「只算一次」必须靠 [targetDate] 这个公历锚点。
     *
     * ⚠️ v2.0.87~2.0.90 期间农历被强制恒为「按年重复」（保存时把 repeatYearly 置真、UI 藏掉开关）。
     * 那条约束已解除 —— 一次性的农历日子（今年祖父母金婚之类）是真实需求。
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
