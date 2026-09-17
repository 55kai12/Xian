package com.xian.focus

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
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
    private const val BONUS_PREFIX = "bonus:"
    private const val KEY_BONUS_MONTH = "bonus_month"
    private const val KEY_BONUS_USED = "bonus_used"
    private const val KEY_LAST_COUNTED = "last_counted_at"

    /**
     * 临时加时：每月 [BONUS_MONTHLY_LIMIT] 次、每次 [BONUS_MINUTES] 分钟。
     * 配额**全局共享**（不按应用各给一份）—— 否则多设几个应用等于总额度翻倍，
     * 「给自己留个小口子」就变成了「口子随便开」。
     */
    const val BONUS_MONTHLY_LIMIT = 3
    const val BONUS_MINUTES = 15

    /** 今日概览：几个应用受限、一共用了多少分钟。 */
    data class Summary(val appCount: Int, val usedMinutes: Long)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun currentMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
    }

    /** 跨天清零当日用量与当日加时。所有读写都先过这里，省掉定时任务和闹钟。 */
    private fun rollover(prefs: SharedPreferences) {
        if (prefs.getString(KEY_DATE, null) == today()) return
        val editor = prefs.edit().putString(KEY_DATE, today())
        prefs.all.keys
            .filter { it.startsWith(USED_PREFIX) || it.startsWith(BONUS_PREFIX) }
            .forEach { editor.remove(it) }
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
            // 只删 limit:，used: / bonus: 留着 —— 当天用量是「今天已经玩了多久」这个事实，
            // 不随限制的开关消失；先选无限制再设回来，已用照算（次日 rollover 自然清零）。
            // 把 used: 一起删掉就是个口子：无限制 ↔ 限额来回切，今天就永远锁不住了。
            editor.remove(LIMIT_PREFIX + packageName)
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
        prefs.edit()
            .putLong(key, prefs.getLong(key, 0L) + seconds)
            .putLong(KEY_LAST_COUNTED, System.currentTimeMillis())
            .apply()
    }

    /** 最近一次计时的时刻；限额页靠它告诉用户「计时到底有没有在工作」。 */
    fun lastCountedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_COUNTED, 0L)

    /** 今日额度 = 设定限额 + 今日加时。 */
    fun effectiveLimitMinutes(context: Context, packageName: String): Int =
        limitMinutes(context, packageName) + bonusMinutes(context, packageName)

    fun bonusMinutes(context: Context, packageName: String): Int {
        val prefs = prefs(context)
        rollover(prefs)
        return prefs.getInt(BONUS_PREFIX + packageName, 0)
    }

    /** 本月还剩几次加时（全局共享）。 */
    fun bonusRemaining(context: Context): Int {
        val prefs = prefs(context)
        val used = if (prefs.getInt(KEY_BONUS_MONTH, -1) == currentMonth()) {
            prefs.getInt(KEY_BONUS_USED, 0)
        } else {
            0
        }
        return (BONUS_MONTHLY_LIMIT - used).coerceAtLeast(0)
    }

    /**
     * 给某个应用加 [BONUS_MINUTES] 分钟并扣一次本月配额。
     * 扣减点唯一：覆盖层的加时按钮。UI 侧只用 [bonusRemaining] 做只读预检。
     */
    fun consumeBonus(context: Context, packageName: String): Boolean {
        if (bonusRemaining(context) <= 0) return false
        val prefs = prefs(context)
        rollover(prefs)
        val used = if (prefs.getInt(KEY_BONUS_MONTH, -1) == currentMonth()) {
            prefs.getInt(KEY_BONUS_USED, 0)
        } else {
            0
        }
        val bonusKey = BONUS_PREFIX + packageName
        prefs.edit()
            .putInt(KEY_BONUS_MONTH, currentMonth())
            .putInt(KEY_BONUS_USED, used + 1)
            .putInt(bonusKey, prefs.getInt(bonusKey, 0) + BONUS_MINUTES)
            .apply()
        // 统计跟着扣减点走：加时只有这一条路径，放这里就不会漏记
        AppLimitStats.recordBonus(context)
        return true
    }

    fun isLocked(context: Context, packageName: String): Boolean {
        val prefs = prefs(context)
        rollover(prefs)
        val limit = prefs.getInt(LIMIT_PREFIX + packageName, 0)
        if (limit <= 0) return false
        val effective = limit + prefs.getInt(BONUS_PREFIX + packageName, 0)
        return prefs.getLong(USED_PREFIX + packageName, 0L) >= effective.toLong() * 60L
    }

    fun summary(context: Context): Summary {
        val packages = limitedPackages(context)
        return Summary(packages.size, packages.sumOf { usedSeconds(context, it) } / 60L)
    }
}
