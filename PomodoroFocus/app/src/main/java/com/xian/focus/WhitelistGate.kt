package com.xian.focus

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 改白名单前的那道闸。
 *
 * 白名单是锁机**唯一**能被用户随时撬动的口子：贤本身永远在白名单里（否则用户没法操作它），
 * 于是锁机期间照样能进「设置 → 锁机设置 → 白名单」，把一个正在用的应用勾上 ——
 * 下一次 `evaluate()` 就让开了，锁机形同虚设。定时锁机更危险：开始前的那一小时正是
 * 「趁还没锁上先加一个」的高发时刻。
 *
 * 所以能改白名单的三个界面（白名单二级页、锁机页的选择器、番茄钟里的自定义锁机弹窗）
 * 全部从这里过一遍：
 *
 *  · **锁机进行中** —— 一行都不给改，弹一次说明就不动；
 *  · **距下次定时锁机开始不到 1 小时** —— 先过 30 秒冷静期，走完才能确认（[COOLDOWN_SECONDS]）；
 *  · 其余情况 —— 直接落盘，不打扰。
 *
 * ⚠️ 闸门架在**落盘那一刻**、不是入口。入口拦只挡得住一半：选择器是异步开的，
 * 用户可以在弹出来之后慢慢挑，挑到锁机已经开始再按保存。
 */
object WhitelistGate {

    /** 冷静期秒数。退出锁机、应用限额各有一个同值常量，三处节奏刻意保持一致。 */
    const val COOLDOWN_SECONDS = 30

    /** 冷却窗口：定时锁机开始前这么久起，改白名单要先过冷静期。 */
    private const val COOLING_WINDOW_MILLIS = 60L * 60_000L

    /**
     * 过完一次冷静期后的放行时长。
     *
     * 不加这个的话，白名单二级页每点一个应用就是一次 30 秒 —— 勾三个应用要等一分半，
     * 「防冲动」会变成「防使用」。所以过一次冷却，接下来两分钟内的改动直接放行；
     * 隔久了再改就重新冷却。
     */
    private const val PASS_WINDOW_MILLIS = 2L * 60_000L

    @Volatile
    private var lastPassedAt = 0L

    fun isLocked(context: Context): Boolean = LockMachineController.isActive(context)

    /**
     * 距下次定时锁机开始的毫秒数；没排定时时段、或还没进冷却窗口时返回 null。
     * 锁机已经在跑时也返回 null —— 那种情况由 [isLocked] 直接拦死，用不着冷却期。
     */
    fun coolingRemainMillis(context: Context): Long? {
        val nextStart = LockMachineScheduler.nextStartAt(context) ?: return null
        val remain = nextStart - System.currentTimeMillis()
        return remain.takeIf { it in 1L..COOLING_WINDOW_MILLIS }
    }

    /** 锁机进行中的说明。入口处也要用（别让用户白选一场），所以单独开出来。 */
    fun notifyLocked(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.whitelist_locked_title)
            .setMessage(R.string.whitelist_locked_message)
            .setPositiveButton(R.string.dialog_confirm, null)
            .show()
    }

    /**
     * 改白名单的唯一入口。放行就 [onApply]，拦下就 [onReject]。
     *
     * @param onApply 落盘。可能被推迟到冷静期之后才执行。
     * @param onReject 没改成时的回滚动作（把界面退回落盘那份）——
     *   取消、点外面关掉、以及冷静期里锁机已经开始，走的都是这一条。
     */
    fun run(
        context: Context,
        scope: CoroutineScope,
        onApply: () -> Unit,
        onReject: () -> Unit = {}
    ) {
        if (isLocked(context)) {
            notifyLocked(context)
            onReject()
            return
        }
        val remain = coolingRemainMillis(context)
        if (remain == null || System.currentTimeMillis() - lastPassedAt < PASS_WINDOW_MILLIS) {
            onApply()
            return
        }
        showCooldown(context, scope, remain, onApply, onReject)
    }

    private fun showCooldown(
        context: Context,
        scope: CoroutineScope,
        remainMillis: Long,
        onApply: () -> Unit,
        onReject: () -> Unit
    ) {
        // 向上取整：还剩 20 秒也要说「1 分钟」，说「还有 0 分钟」等于没说
        val minutesToLock = ((remainMillis + 59_999L) / 60_000L).toInt()
        var applied = false
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.whitelist_cooldown_title)
            .setMessage(context.getString(R.string.whitelist_cooldown_message, minutesToLock))
            .setPositiveButton(R.string.whitelist_cooldown_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                // 只要没落盘就回滚：取消、按返回、点外面关掉，都走这条
                if (!applied) onReject()
            }
            .show()
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirm.isEnabled = false
        scope.launch {
            var remainSeconds = COOLDOWN_SECONDS
            while (remainSeconds > 0 && dialog.isShowing) {
                dialog.setMessage(
                    context.getString(R.string.whitelist_cooldown_counting, minutesToLock, remainSeconds)
                )
                delay(1_000L)
                remainSeconds--
            }
            if (!dialog.isShowing) return@launch
            dialog.setMessage(context.getString(R.string.whitelist_cooldown_ready, minutesToLock))
            confirm.isEnabled = true
        }
        confirm.setOnClickListener {
            // 这 30 秒里锁机可能已经开始（定时时段正好卡在中间）—— 那一刻起白名单就锁死了，
            // 不能因为「冷静期熬过去了」就放行。原地把框改成锁定说明，不必再另弹一个。
            if (isLocked(context)) {
                dialog.setTitle(R.string.whitelist_locked_title)
                dialog.setMessage(context.getString(R.string.whitelist_locked_message))
                confirm.setText(R.string.dialog_confirm)
                confirm.setOnClickListener { dialog.dismiss() }
                return@setOnClickListener
            }
            applied = true
            lastPassedAt = System.currentTimeMillis()
            dialog.dismiss()
            // 绕 scope 走一趟而不是直接调：视图已经销毁时协程不会执行，
            // 免得回调里去碰已经置空的 binding。
            scope.launch { onApply() }
        }
    }
}
