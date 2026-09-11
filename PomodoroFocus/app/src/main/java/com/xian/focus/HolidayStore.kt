package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 法定节假日。
 *
 * 春节 / 端午 / 中秋 是农历节日，按公历写死会年年出错；本类用农历数据表
 * （1900-2100）现场推算它们的公历日期，清明按节气公式推算。换年不用改代码。
 *
 * 关于「调休」：哪几个周末补班、假期怎么凑，是国务院当年的公告，
 * 无法由历法推算。本类只按固定规律标记节日区间（见 CORE_SPANS），不臆造调休安排。
 */
object HolidayStore {

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
    private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

    private val holidayCache = HashMap<Int, Map<String, List<Int>>>()

    /** 固定区间：月/日为公历；春节与端午/中秋是农历，另算。 */
    private val FIXED_SPANS = arrayOf(
        intArrayOf(R.string.holiday_new_year, 1, 1, 1),
        intArrayOf(R.string.holiday_labour, 5, 1, 5),
        intArrayOf(R.string.holiday_national, 10, 1, 7)
    )

    fun isHoliday(year: Int, month: Int, day: Int): Boolean =
        holidaysOf(year).containsKey(dateKey(month, day))

    /**
     * 节日名（一天可能撞上两个，用「·」连起来）。
     * 存的是资源 id，所以跟着界面语言走。
     */
    fun getHolidayName(context: Context, year: Int, month: Int, day: Int): String? =
        holidaysOf(year)[dateKey(month, day)]?.joinToString("·") { context.getString(it) }

    // ---------------- 农历换算 ----------------

    private fun leapMonth(y: Int): Int = LUNAR_INFO[y - BASE_YEAR] and 0xF

    private fun leapDays(y: Int): Int =
        if (leapMonth(y) == 0) 0
        else if (LUNAR_INFO[y - BASE_YEAR] and 0x10000 != 0) 30 else 29

    private fun monthDays(y: Int, m: Int): Int =
        if (LUNAR_INFO[y - BASE_YEAR] and (0x10000 shr m) != 0) 30 else 29

    private fun yearDays(y: Int): Int {
        var total = 348
        var bit = 0x8000
        while (bit > 0x8) {
            if (LUNAR_INFO[y - BASE_YEAR] and bit != 0) total++
            bit = bit shr 1
        }
        return total + leapDays(y)
    }

    /** 该年正月初一距 1900-01-31 的天数 */
    private fun lunarNewYearOffset(y: Int): Int {
        var offset = 0
        for (i in BASE_YEAR until y) offset += yearDays(i)
        return offset
    }

    /** 该年农历 month 月 day 日距 1900-01-31 的天数 */
    private fun lunarDayOffset(y: Int, month: Int, day: Int): Int {
        var offset = lunarNewYearOffset(y)
        for (m in 1 until month) {
            offset += monthDays(y, m)
            if (leapMonth(y) == m) offset += leapDays(y)
        }
        return offset + day - 1
    }

    /** 公历日期距 1900-01-31 的天数 */
    private fun solarOffset(y: Int, month: Int, day: Int): Int {
        val target = Calendar.getInstance().apply {
            clear()
            set(y, month - 1, day)
        }
        val base = Calendar.getInstance().apply {
            clear()
            set(BASE_YEAR, Calendar.JANUARY, 31)
        }
        return ((target.timeInMillis - base.timeInMillis) / DAY_MILLIS).toInt()
    }

    /** 清明：21 世纪用节气近似式，其余年份取 4 月 5 日 */
    private fun qingmingDay(y: Int): Int =
        if (y in 2000..2099) {
            val yy = y - 2000
            (yy * 0.2422 + 4.81).toInt() - yy / 4
        } else 5

    // ---------------- 节日表 ----------------

    private fun dateKey(month: Int, day: Int) = "%02d-%02d".format(month, day)

    private fun holidaysOf(year: Int): Map<String, List<Int>> {
        holidayCache[year]?.let { return it }
        val names = HashMap<String, MutableList<Int>>()

        fun addSpan(startOffset: Int, days: Int, nameRes: Int) {
            val c = Calendar.getInstance().apply {
                clear()
                set(BASE_YEAR, Calendar.JANUARY, 31)
                add(Calendar.DAY_OF_MONTH, startOffset)
            }
            repeat(days) {
                val key = dateKey(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
                names.getOrPut(key) { mutableListOf() }.add(nameRes)
                c.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        for (row in FIXED_SPANS) {
            addSpan(solarOffset(year, row[1], row[2]), row[3], row[0])
        }

        // 春节：除夕（正月初一前一天）起 7 天
        addSpan(lunarNewYearOffset(year) - 1, 7, R.string.holiday_spring_festival)
        // 清明：当天起 3 天连休
        addSpan(solarOffset(year, 4, qingmingDay(year)), 3, R.string.holiday_qingming)
        // 端午：农历五月初五
        addSpan(lunarDayOffset(year, 5, 5), 1, R.string.holiday_duanwu)
        // 中秋：农历八月十五
        addSpan(lunarDayOffset(year, 8, 15), 1, R.string.holiday_mid_autumn)

        val result = names.mapValues { (_, list) -> list.distinct() }
        holidayCache[year] = result
        return result
    }

    // ---------------- 开关 ----------------

    private var showHolidayCache: Boolean? = null
    private var lastCacheTime: Long = 0

    fun isShowHolidayEnabled(context: Context): Boolean {
        val now = System.currentTimeMillis()
        // 缓存5秒，避免频繁读SharedPreferences
        if (showHolidayCache != null && now - lastCacheTime < 5000) {
            return showHolidayCache!!
        }
        showHolidayCache = context.getSharedPreferences("event_settings", 0)
            .getBoolean("show_holiday", true)
        lastCacheTime = now
        return showHolidayCache!!
    }

    fun invalidateCache() {
        showHolidayCache = null
    }
}
