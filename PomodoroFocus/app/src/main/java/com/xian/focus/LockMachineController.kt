package com.xian.focus

import android.content.Context

object LockMachineController {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_END_AT = "lock_end_at"
    private const val KEY_WHITELIST = "lock_whitelist"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun start(context: Context, durationMinutes: Int, whitelist: Set<String>) {
        val endAt = System.currentTimeMillis() + durationMinutes.toLong() * 60_000L
        prefs(context).edit()
            .putLong(KEY_END_AT, endAt)
            .putStringSet(KEY_WHITELIST, whitelist)
            .apply()
    }

    fun stop(context: Context) {
        prefs(context).edit()
            .remove(KEY_END_AT)
            .apply()
    }

    fun isActive(context: Context): Boolean =
        remainingMillis(context) > 0

    fun remainingMillis(context: Context): Long {
        val endAt = prefs(context).getLong(KEY_END_AT, 0L)
        return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
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
        packageName == context.applicationContext.packageName || whitelist(context).contains(packageName)
}
