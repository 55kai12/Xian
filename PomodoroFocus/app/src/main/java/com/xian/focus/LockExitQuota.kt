package com.xian.focus

import android.content.Context
import java.util.Calendar

/**
 * 主动退出锁机的月度额度：每个自然月最多 [MONTHLY_LIMIT] 次。
 * 锁机自然到期不算退出，不消耗额度。
 *
 * 扣减点唯一：LockMachineService.stopLock()。UI 侧只用 [canExit]/[remaining] 做预检，
 * 别在调用方 consume，否则一条退出路径会被扣两次。
 */
object LockExitQuota {
    /**
     * 每月额度。**别调回 2**：定时时段反复自然到期看起来也像「退出」，用户遇到几次
     * 「今天已经退过了」就会认定这个功能坏了。4 次足够覆盖「真有事要进去拿东西」，
     * 又还不至于随手就能靠额度把每天重复的时段全躲掉。
     */
    const val MONTHLY_LIMIT = 4

    /** 主动退出前的冷静期秒数。覆盖层弹窗和锁机设置页共用。 */
    const val COOLDOWN_SECONDS = 30

    private const val PREFS_NAME = "lock_machine_prefs"
    private const val KEY_MONTH = "exit_quota_month"
    private const val KEY_USED = "exit_quota_used"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun currentMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
    }

    private fun used(context: Context): Int {
        val prefs = prefs(context)
        return if (prefs.getInt(KEY_MONTH, -1) == currentMonth()) {
            prefs.getInt(KEY_USED, 0)
        } else {
            0
        }
    }

    fun remaining(context: Context): Int = (MONTHLY_LIMIT - used(context)).coerceAtLeast(0)

    fun canExit(context: Context): Boolean = remaining(context) > 0

    /** 检查并扣减一次额度，返回是否放行。 */
    fun consume(context: Context): Boolean {
        if (!canExit(context)) return false
        prefs(context).edit()
            .putInt(KEY_MONTH, currentMonth())
            .putInt(KEY_USED, used(context) + 1)
            .apply()
        return true
    }
}
