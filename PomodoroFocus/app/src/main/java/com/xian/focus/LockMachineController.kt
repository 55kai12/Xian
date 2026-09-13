package com.xian.focus

import android.content.Context

/**
 * 自定义锁机的运行状态 + 白名单。
 *
 * 关键约定：**白名单是独立设置，不是某次锁机的附属参数**。
 * 早先 `start()` 会把白名单一起写进去，于是只有在「开始锁机」那一刻选择的白名单才会生效 ——
 * 用户改完白名单却没点开始（或只保存了定时时段），一重建视图这份选择就白选了，
 * 界面上看起来就是「白名单莫名其妙没了」。现在读写分成两件事，锁机的起止只记时刻。
 */
object LockMachineController {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_START_AT = "lock_start_at"
    private const val KEY_END_AT = "lock_end_at"
    private const val KEY_WHITELIST = "lock_whitelist"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(context: Context, durationMinutes: Int) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_START_AT, now)
            .putLong(KEY_END_AT, now + durationMinutes.toLong() * 60_000L)
            .apply()
    }

    fun stop(context: Context) {
        prefs(context).edit()
            .remove(KEY_START_AT)
            .remove(KEY_END_AT)
            .apply()
    }

    fun isActive(context: Context): Boolean =
        remainingMillis(context) > 0

    fun remainingMillis(context: Context): Long {
        val endAt = prefs(context).getLong(KEY_END_AT, 0L)
        return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 本次锁机的预计结束时刻；跨日锁机靠它才说得清「到明天几点」。 */
    fun endAt(context: Context): Long = prefs(context).getLong(KEY_END_AT, 0L)

    fun endText(context: Context): String {
        val endAt = endAt(context)
        return if (endAt <= 0L) "" else TimeLabels.relative(context, endAt)
    }

    /**
     * 本次锁机实际已锁时长：开始时刻到现在/到结束时刻中较早的一个。
     * 供 LockStats 在锁机结束（stop/到期）时、清掉 prefs 之前调用。
     */
    fun elapsedMillis(context: Context): Long {
        val prefs = prefs(context)
        val startAt = prefs.getLong(KEY_START_AT, 0L)
        if (startAt <= 0L || !isActive(context)) return 0L
        val endAt = prefs(context).getLong(KEY_END_AT, 0L)
        val now = System.currentTimeMillis()
        val effectiveEnd = if (endAt > 0L) minOf(endAt, now) else now
        return (effectiveEnd - startAt).coerceAtLeast(0L)
    }

    fun remainingText(context: Context): String {
        val totalSeconds = (remainingMillis(context) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /**
     * 白名单：用户选完立即落盘，不依附于任何一次锁机。
     * 返回副本，避免调用方拿到的可变集合被改后污染 SharedPreferences 里那份（内存里是同一实例）。
     */
    fun whitelist(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet())

    fun saveWhitelist(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_WHITELIST, HashSet(packages)).apply()
    }

    fun isAllowed(context: Context, packageName: String): Boolean =
        packageName == context.applicationContext.packageName ||
            // 系统必需组件（来电、输入法、状态栏等）永远放行，
            // 否则会盖住来电界面和输入法，导致接不了电话、白名单应用里打不了字。
            SystemAppAllowlist.isEssential(context.applicationContext, packageName) ||
            whitelist(context).contains(packageName)
}
