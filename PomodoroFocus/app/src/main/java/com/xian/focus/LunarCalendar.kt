package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 农历换算（1900-2100 数据表）。
 *
 * 这张表和这套算法原先长在 [HolidayStore] 里、并且是私有的，只为「春节/端午/中秋是哪天」服务。
 * 倒数日要存用户的农历月/日、还要反推每年的公历日期，两处必须共用同一张表、同一套算法 ——
 * 各留一份迟早会算出不一样的春节。所以抽到这里，[HolidayStore] 只保留节日的组织逻辑。
 *
 * 全部换算都是「距 1900-01-31（农历 1900 年正月初一）多少天」的加减，不依赖任何系统 API，
 * 但**结果按本机时区的 Calendar 落地**：跨时区飞不会把农历日期算歪，因为农历本身不含时区。
 */
object LunarCalendar {

    /** 农历数据表 1900-2100，每年一项 20 位整数
     *  bit16 = 闰月天数(1=30/0=29)；bit15..4 = 正月起各月天数；bit3..0 = 闰月月份(0=无闰) */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573, 0x052b0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63, 0x09370, 0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x0a2e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0, 0x0a6d0, 0x055d4,
        0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50, 0x055a0, 0x0aba4, 0x0a5b0, 0x052b0,
        0x0b273, 0x06930, 0x07337, 0x06aa0, 0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160,
        0x0e968, 0x0d520, 0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520
    )

    private const val BASE_YEAR = 1900
    private const val MIN_YEAR = BASE_YEAR
    private const val MAX_YEAR = BASE_YEAR + 200
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    /** 一个农历日期；[leap] 为 true 表示闰月（如「闰六月十五」）。 */
    data class LunarDate(val year: Int, val month: Int, val day: Int, val leap: Boolean)

    // ------------------------------------------------------------------ 数据表

    private fun leapMonth(year: Int): Int = LUNAR_INFO[year - BASE_YEAR] and 0xF

    private fun leapDays(year: Int): Int =
        if (leapMonth(year) == 0) 0
        else if (LUNAR_INFO[year - BASE_YEAR] and 0x10000 != 0) 30 else 29

    private fun monthDays(year: Int, month: Int): Int =
        if (LUNAR_INFO[year - BASE_YEAR] and (0x10000 shr month) != 0) 30 else 29

    private fun yearDays(year: Int): Int {
        var total = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if (LUNAR_INFO[year - BASE_YEAR] and bit != 0) total++
            bit = bit shr 1
        }
        return total + leapDays(year)
    }

    /** 某年农历 month 月有多少天。[leap] 为 true 且该年确实有这个闰月时取闰月天数。 */
    fun daysInMonth(year: Int, month: Int, leap: Boolean): Int =
        if (leap && leapMonth(year) == month) leapDays(year) else monthDays(year, month)

    /** 该年正月初一距 1900-01-31 的天数。 */
    fun newYearOffset(year: Int): Int {
        var offset = 0
        for (y in BASE_YEAR until year) offset += yearDays(y)
        return offset
    }

    /**
     * 该年农历 month 月 day 日距 1900-01-31 的天数。
     *
     * [leap] 只在「该年确实有这个闰月」时才多算一个闰月，否则按普通月份算 ——
     * 用户写「闰六月十五」，碰上没有闰六月的年份（绝大多数年份），落到六月十五
     * 比拒绝这条记录合理。日号超出该月天数时压到月末（廿九/三十），同理：
     * 不为了一个边角情况让用户录不进来。
     */
    fun lunarDayOffset(year: Int, month: Int, day: Int, leap: Boolean = false): Int {
        val m = month.coerceIn(1, 12)
        var offset = newYearOffset(year)
        for (i in 1 until m) {
            offset += monthDays(year, i)
            if (leapMonth(year) == i) offset += leapDays(year)
        }
        val leapHere = leap && leapMonth(year) == m
        if (leapHere) offset += monthDays(year, m)
        val daysInThisMonth = if (leapHere) leapDays(year) else monthDays(year, m)
        return offset + day.coerceIn(1, daysInThisMonth) - 1
    }

    /** 公历日期距 1900-01-31 的天数。 */
    fun solarOffset(year: Int, month: Int, day: Int): Int = solarOffset(
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    )

    private fun solarOffset(timeMillis: Long): Int {
        val base = Calendar.getInstance().apply {
            clear()
            set(BASE_YEAR, Calendar.JANUARY, 31)
        }
        val target = Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ((target.timeInMillis - base.timeInMillis) / DAY_MILLIS).toInt()
    }

    // ------------------------------------------------------------------ 双向换算

    /** 农历 → 公历（当日 00:00 的时间戳）。 */
    fun lunarToSolar(year: Int, month: Int, day: Int, leap: Boolean = false): Long {
        val y = year.coerceIn(MIN_YEAR, MAX_YEAR)
        return Calendar.getInstance().apply {
            clear()
            set(BASE_YEAR, Calendar.JANUARY, 31)
            add(Calendar.DAY_OF_MONTH, lunarDayOffset(y, month, day, leap))
        }.timeInMillis
    }

    /** 公历 → 农历。 */
    fun solarToLunar(timeMillis: Long): LunarDate {
        var offset = solarOffset(timeMillis).coerceAtLeast(0)
        var year = MIN_YEAR
        while (year < MAX_YEAR && offset >= yearDays(year)) {
            offset -= yearDays(year)
            year++
        }
        var month = 1
        var leap = false
        while (month <= 12) {
            val days = monthDays(year, month)
            if (offset < days) break
            offset -= days
            if (leapMonth(year) == month) {
                val extra = leapDays(year)
                if (offset < extra) {
                    leap = true
                    break
                }
                offset -= extra
            }
            month++
        }
        return LunarDate(year, month.coerceAtMost(12), offset.toInt() + 1, leap)
    }

    /**
     * 从 [from] 起，下一次出现该农历月/日的公历时刻。
     *
     * 口径与公历重复**保持一致**：拿 [from] 的即时时刻比，不按 0 点归零 ——
     * 于是「今天正好是这一天」会顺延到明年，跟公历条目现有行为完全一样。
     * 两处口径必须一致，否则列表的「还有 N 天」和提醒的触发点会各算各的。
     */
    fun nextOccurrence(
        month: Int,
        day: Int,
        leap: Boolean,
        from: Long = System.currentTimeMillis()
    ): Long {
        val startYear = solarToLunar(from).year
        for (i in 0..2) {
            val year = startYear + i
            if (year > MAX_YEAR) break
            val candidate = lunarToSolar(year, month, day, leap)
            if (candidate > from) return candidate
        }
        return lunarToSolar((startYear + 1).coerceAtMost(MAX_YEAR), month, day, leap)
    }

    // ------------------------------------------------------------------ 显示

    /** 月份名（正月…腊月），供选择器直接当 displayedValues 用。 */
    fun monthNames(context: Context): Array<String> =
        context.resources.getStringArray(R.array.lunar_months)

    /** 日期名（初一…三十）。 */
    fun dayNames(context: Context): Array<String> =
        context.resources.getStringArray(R.array.lunar_days)

    /** 「八月十五」/「闰六月初一」；闰月加前缀。 */
    fun format(context: Context, month: Int, day: Int, leap: Boolean): String {
        val months = monthNames(context)
        val days = dayNames(context)
        val monthName = months.getOrElse(month - 1) { month.toString() }
        val dayName = days.getOrElse(day - 1) { day.toString() }
        val text = context.getString(R.string.lunar_date_format, monthName, dayName)
        return if (leap) context.getString(R.string.lunar_leap_prefix) + text else text
    }
}
