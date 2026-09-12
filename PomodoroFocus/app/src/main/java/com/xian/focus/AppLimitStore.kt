package com.xian.focus

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用限额：给某个应用设一天能玩多少分钟，用完当天锁死，次日 0 点自动重置。
 *
 * 用量不读系统 UsageStats，而是由 FocusLockAccessibilityService 每秒累加。
 * 这样免掉「使用情况访问」这个特殊权限（要跳系统设置页单独授权），
 * 代价是只在无障碍服务运行时计时 —— 页面里显式提示了这一点。
 */
object AppLimitStore {
    private const val PREFS_NAME = "app_limit_prefs"
    private const val KEY_DATE = "used_date"
    private const val LIMIT_PREFIX = "limit:"
    private const val USED_PREFIX = "used:"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 跨天清零当日用量。所有读写都先过这里，省掉定时任务和闹钟。 */
    private fun rollover(prefs: SharedPreferences) {
        if (prefs.getString(KEY_DATE, null) == today()) return
        val editor = prefs.edit().putString(KEY_DATE, today())
        prefs.all.keys.filter { it.startsWith(USED_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 每日限额分钟数；0 表示没限制。 */
    fun limitMinutes(context: Context, packageName: String): Int {
        val prefs = prefs(context)
        rollover(prefs)
        return prefs.getInt(LIMIT_PREFIX + packageName, 0)
    }

    /** minutes <= 0 表示取消限制。 */
    fun setLimit(context: Context, packageName: String, minutes: Int) {
        val prefs = prefs(context)
        rollover(prefs)
        val editor = prefs.edit()
        if (minutes <= 0) {
            editor.remove(LIMIT_PREFIX + packageName).remove(USED_PREFIX + packageName)
        } else {
            editor.putInt(LIMIT_PREFIX + packageName, minutes)
        }
        editor.apply()
    }

    fun limitedPackages(context: Context): List<String> {
        val prefs = prefs(context)
        rollover(prefs)
        return prefs.all.keys.filter { it.startsWith(LIMIT_PREFIX) }
            .map { it.removePrefix(LIMIT_PREFIX) }
    }

    fun usedSeconds(context: Context, packageName: String): Long {
        val prefs = prefs(context)
        rollover(prefs)
        return prefs.getLong(USED_PREFIX + packageName, 0L)
    }

    /** 只对设了限额的应用累加，其他应用不产生任何写入。 */
    fun addSeconds(context: Context, packageName: String, seconds: Long) {
        if (seconds <= 0L) return
        val prefs = prefs(context)
        rollover(prefs)
        if (prefs.getInt(LIMIT_PREFIX + packageName, 0) <= 0) return
        val key = USED_PREFIX + packageName
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + seconds).apply()
    }

    fun isLocked(context: Context, packageName: String): Boolean {
        val minutes = limitMinutes(context, packageName)
        if (minutes <= 0) return false
        return usedSeconds(context, packageName) >= minutes.toLong() * 60L
    }
}
