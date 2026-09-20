package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 法定节假日。
 *
 * 春节 / 端午 / 中秋 是农历节日，按公历写死会年年出错；本类用 [LunarCalendar]
 * （农历数据表 1900-2100）现场推算它们的公历日期，清明按节气公式推算。换年不用改代码。
 *
 * 关于「调休」：哪几个周末补班、假期怎么凑，是国务院当年的公告，
 * 无法由历法推算。本类只按固定规律标记节日区间（见 FIXED_SPANS），不臆造调休安排。
 */
object HolidayStore {

    /** 农历 1900 年正月初一对应的公历日期，换算基准。 */
    private const val BASE_YEAR = 1900

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
            addSpan(LunarCalendar.solarOffset(year, row[1], row[2]), row[3], row[0])
        }

        // 春节：除夕（正月初一前一天）起 7 天
        addSpan(LunarCalendar.newYearOffset(year) - 1, 7, R.string.holiday_spring_festival)
        // 清明：当天起 3 天连休
        addSpan(LunarCalendar.solarOffset(year, 4, qingmingDay(year)), 3, R.string.holiday_qingming)
        // 端午：农历五月初五
        addSpan(LunarCalendar.lunarDayOffset(year, 5, 5), 1, R.string.holiday_duanwu)
        // 中秋：农历八月十五
        addSpan(LunarCalendar.lunarDayOffset(year, 8, 15), 1, R.string.holiday_mid_autumn)

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
