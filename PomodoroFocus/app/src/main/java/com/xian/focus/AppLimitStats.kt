package com.xian.focus

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 本月应用限额统计：被锁次数、最常被锁的应用、加时用了几次。
 *
 * 与 LockStats / LockExitQuota 同一套路：键里带「年*100+月」，读的时候月份对不上就当 0，
 * 跨月自动重置，不需要定时清零。
 *
 * 「被锁次数」按**应用 × 天**去重：同一个应用同一天被锁多次只算一次 ——
 * 否则用户切出去再切回来就把数字刷上去了，看着吓人还不真实。
 */
object AppLimitStats {
    private const val PREFS_NAME = "app_limit_prefs"
    private const val KEY_MONTH = "limit_stats_month"
    private const val KEY_LOCKS = "limit_stats_locks"
    private const val KEY_BONUS = "limit_stats_bonus"
    private const val KEY_PKG_COUNTS = "limit_stats_pkg_counts"
    private const val KEY_TODAY_MARK = "limit_stats_today"

    data class Snapshot(
        val locks: Int,
        val bonusUsed: Int,
        val topApp: String?,
        val topAppCount: Int
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
    }

    private fun isCurrentMonth(prefs: SharedPreferences): Boolean =
        prefs.getInt(KEY_MONTH, -1) == currentMonth()

    /** 某个应用今天的额度第一次用尽时记一笔；同一天同一应用重复调用无效果。 */
    fun recordLock(context: Context, packageName: String) {
        val prefs = prefs(context)
        val mark = prefs.getString(KEY_TODAY_MARK, null)
        val lockedToday = if (mark != null && mark.substringBefore('|') == today()) {
            mark.substringAfter('|').split(',').filter { it.isNotBlank() }.toMutableSet()
        } else {
            mutableSetOf()
        }
        if (!lockedToday.add(packageName)) return

        val reset = !isCurrentMonth(prefs)
        val counts = if (reset) mutableMapOf() else pkgCounts(prefs)
        counts[packageName] = (counts[packageName] ?: 0) + 1

        prefs.edit()
            .putString(KEY_TODAY_MARK, today() + "|" + lockedToday.joinToString(","))
            .putInt(KEY_MONTH, currentMonth())
            .putInt(KEY_LOCKS, (if (reset) 0 else prefs.getInt(KEY_LOCKS, 0)) + 1)
            .putStringSet(KEY_PKG_COUNTS, counts.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    /** 记一次「临时加时」的使用。 */
    fun recordBonus(context: Context) {
        val prefs = prefs(context)
        val reset = !isCurrentMonth(prefs)
        prefs.edit()
            .putInt(KEY_MONTH, currentMonth())
            .putInt(KEY_BONUS, (if (reset) 0 else prefs.getInt(KEY_BONUS, 0)) + 1)
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        if (!isCurrentMonth(prefs)) return Snapshot(0, 0, null, 0)
        val top = pkgCounts(prefs).maxByOrNull { it.value }
        return Snapshot(
            locks = prefs.getInt(KEY_LOCKS, 0),
            bonusUsed = prefs.getInt(KEY_BONUS, 0),
            topApp = top?.key,
            topAppCount = top?.value ?: 0
        )
    }

    /** prefs 里按 "包名|次数" 存；包名不含 '|'，所以取最后一个分隔符拆开即可。 */
    private fun pkgCounts(prefs: SharedPreferences): MutableMap<String, Int> =
        (prefs.getStringSet(KEY_PKG_COUNTS, emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                val index = entry.lastIndexOf('|')
                if (index <= 0) null
                else entry.substring(0, index) to (entry.substring(index + 1).toIntOrNull() ?: 0)
            }
            .toMap()
            .toMutableMap()
}
