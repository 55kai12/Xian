package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 法定节假日数据，用于日历标记。
 * 格式：MM-dd -> 节日名称
 */
object HolidayStore {

    private val holidays = mapOf(
        "01-01" to "元旦",
        "02-10" to "春节",
        "02-11" to "春节",
        "02-12" to "春节",
        "02-13" to "春节",
        "02-14" to "春节",
        "02-15" to "春节",
        "02-16" to "春节",
        "02-17" to "春节",
        "04-04" to "清明",
        "04-05" to "清明",
        "04-06" to "清明",
        "05-01" to "劳动节",
        "05-02" to "劳动节",
        "05-03" to "劳动节",
        "05-04" to "劳动节",
        "05-05" to "劳动节",
        "06-19" to "端午",
        "06-20" to "端午",
        "06-21" to "端午",
        "10-01" to "国庆",
        "10-02" to "国庆",
        "10-03" to "国庆",
        "10-04" to "国庆",
        "10-05" to "国庆",
        "10-06" to "国庆",
        "10-07" to "国庆"
    )

    private var showHolidayCache: Boolean? = null
    private var lastCacheTime: Long = 0

    fun isHoliday(month: Int, day: Int): Boolean {
        val key = "%02d-%02d".format(month, day)
        return holidays.containsKey(key)
    }

    fun getHolidayName(month: Int, day: Int): String? {
        val key = "%02d-%02d".format(month, day)
        return holidays[key]
    }

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
