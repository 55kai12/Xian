package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 本月锁机统计：次数、累计时长、忍住没退出的次数。
 *
 * 与 [LockExitQuota] 同一套路：按自然月聚合存 SharedPreferences，
 * 键里带「年*100+月」，读的时候月份对不上就当 0，跨月自动重置，不需要定时清零。
 *
 * 记录点只有两个，都在 LockMachineService：自然到期（heldOut=true）与
 * 额度内主动退出（heldOut=false）。开始时刻由 LockMachineController.start() 记录。
 */
object LockStats {
    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_MONTH = "lock_stats_month"
    private const val KEY_SESSIONS = "lock_stats_sessions"
    private const val KEY_MINUTES = "lock_stats_minutes"
    private const val KEY_HELD = "lock_stats_held"

    data class Snapshot(val sessions: Int, val minutes: Int, val held: Int)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
    }

    private fun isInCurrentMonth(context: Context): Boolean =
        prefs(context).getInt(KEY_MONTH, -1) == currentMonth()

    /** 记一笔锁机结束。时长从锁机开始时刻算，不足 1 分钟按 1 分钟记。 */
    fun record(context: Context, heldOut: Boolean) {
        val minutes = ((LockMachineController.elapsedMillis(context) + 59_999L) / 60_000L)
            .toInt()
            .coerceAtLeast(1)
        val reset = !isInCurrentMonth(context)
        val prefs = prefs(context)
        prefs.edit()
            .putInt(KEY_MONTH, currentMonth())
            .putInt(KEY_SESSIONS, (if (reset) 0 else prefs.getInt(KEY_SESSIONS, 0)) + 1)
            .putInt(KEY_MINUTES, (if (reset) 0 else prefs.getInt(KEY_MINUTES, 0)) + minutes)
            .putInt(KEY_HELD, (if (reset) 0 else prefs.getInt(KEY_HELD, 0)) + if (heldOut) 1 else 0)
            .apply()
    }

    fun snapshot(context: Context): Snapshot {
        if (!isInCurrentMonth(context)) return Snapshot(0, 0, 0)
        val prefs = prefs(context)
        return Snapshot(
            sessions = prefs.getInt(KEY_SESSIONS, 0),
            minutes = prefs.getInt(KEY_MINUTES, 0),
            held = prefs.getInt(KEY_HELD, 0)
        )
    }
}
