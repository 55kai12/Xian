package com.xian.focus

import android.content.Context
import com.xian.focus.data.TimerSettingsPreferences

/**
 * 「锁机专注」是否正在生效的唯一权威判定。
 *
 * 旧实现把 lockEnabled / timerRunning 两个标志位放在内存静态变量里，
 * 进程被系统回收后重新拉起时会双双变回默认值 false，锁机界面静默消失 ——
 * 这正好是锁机类应用最怕的绕过方式（任务管理器划掉应用即可解锁）。
 *
 * 现在改为从持久化的数据推导：
 * - 用户是否开启锁机 → TimerSettingsPreferences（SharedPreferences 落盘）
 * - 番茄钟是否在跑 → 持久化的 timer session（含阶段结束时间戳）
 * 两者都不依赖任何存活的界面或内存状态。
 */
object LockController {

    /** 用户是否开启了「锁机专注」开关。 */
    fun isLockEnabled(context: Context): Boolean =
        TimerSettingsPreferences(context.applicationContext).isLockEnabled()

    /**
     * 锁机是否正在生效：用户开了锁机，且番茄钟处于非空闲状态。
     *
     * 注意 PAUSED 也算「生效」：否则用户只要按一下暂停就能临时解锁去刷别的应用，
     * 等于给锁机开了后门。
     *
     * 另外会校验阶段结束时间，防止进程被杀死后残留的过期 session
     * 把用户永久锁在应用外面。
     */
    fun isLockActive(context: Context): Boolean {
        val prefs = TimerSettingsPreferences(context.applicationContext)
        if (!prefs.isLockEnabled()) return false
        val session = prefs.getSession() ?: return false
        val status = runCatching { TimerStatus.valueOf(session.status) }.getOrNull() ?: return false
        return when (status) {
            TimerStatus.IDLE -> false
            TimerStatus.PAUSED -> true
            // 正在倒计时：确认这一段还没走完。phaseEndAt <= 0 代表旧版本写入的
            // session（没有该字段），此时沿用「视为生效」的保守策略。
            else -> session.phaseEndAt <= 0L || session.phaseEndAt > System.currentTimeMillis()
        }
    }
}
