package com.xian.focus

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 「昨天有 N 个事件尚未达成」横幅的状态。
 *
 * 两条规则，都按**自然日**存、跨日自动失效（存日期串，读时对不上就当没记过 ——
 * 跟锁机额度/限额用量同一套写法，不给用户留「什么时候会重置」的疑问）：
 *
 *  · [KEY_SHOWN_DAY] —— **每天只弹一次**。用户给的参考图是「提醒」，不是「常驻横幅」：
 *    每次进任务页都弹一遍就成了噪音，最后的结果是用户学会无视它。
 *  · [KEY_MUTED_DAY] —— 点过「今日不再提醒」的日子，当天内不再弹（第二天照常）。
 *
 * ⚠️ 这个 state 刻意**不进备份**：它是「今天弹没弹过」的一次性状态，
 * 换手机之后重新弹一次反而更合理。与 `TaskSkipStore` 那种「用户攒的东西」不同。
 */
object YesterdayBannerState {
    private const val PREFS_NAME = "event_settings"
    private const val KEY_SHOWN_DAY = "yesterday_banner_shown_day"
    private const val KEY_MUTED_DAY = "yesterday_banner_muted_day"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String {
        val calendar = Calendar.getInstance()
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 今天还能不能弹：没弹过、也没被静音。 */
    fun shouldShow(context: Context): Boolean {
        val day = today()
        val prefs = prefs(context)
        return prefs.getString(KEY_SHOWN_DAY, null) != day &&
            prefs.getString(KEY_MUTED_DAY, null) != day
    }

    /** 标记今天已经弹过（显示的那一刻就记，用户划走也不再弹）。 */
    fun markShown(context: Context) {
        prefs(context).edit().putString(KEY_SHOWN_DAY, today()).apply()
    }

    /** 「今日不再提醒」：静音到明天。 */
    fun muteToday(context: Context) {
        val day = today()
        prefs(context).edit()
            .putString(KEY_SHOWN_DAY, day)
            .putString(KEY_MUTED_DAY, day)
            .apply()
    }
}
