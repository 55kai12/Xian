package com.xian.focus

import android.content.Context

object LockMachineController {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_START_AT = "lock_start_at"
    private const val KEY_END_AT = "lock_end_at"
    private const val KEY_WHITELIST = "lock_whitelist"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(context: Context, durationMinutes: Int, whitelist: Set<String>) {
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_START_AT, now)
            .putLong(KEY_END_AT, now + durationMinutes.toLong() * 60_000L)
            .putStringSet(KEY_WHITELIST, whitelist)
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

    fun whitelist(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()

    fun isAllowed(context: Context, packageName: String): Boolean =
        packageName == context.applicationContext.packageName ||
            // 系统必需组件（来电、输入法、状态栏等）永远放行，
            // 否则会盖住来电界面和输入法，导致接不了电话、白名单应用里打不了字。
            SystemAppAllowlist.isEssential(context.applicationContext, packageName) ||
            whitelist(context).contains(packageName)
}
