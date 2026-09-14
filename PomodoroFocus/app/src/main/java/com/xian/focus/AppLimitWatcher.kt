package com.xian.focus

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

/**
 * 应用限额的记账与拦截 —— 跑在守护服务 [GuardService] 里。
 *
 * 前台应用**不**来自无障碍的窗口事件，而是读系统自己的使用记录（UsageStats，数字健康读的那份）。
 * 原因：窗口事件只在我们的进程活着时才会送到，而国产 ROM 一冻结 / 清掉进程，事件就断了 ——
 * 表现正是「玩了两小时一秒没记上」「划掉后台就拦不住」。
 * 使用记录是系统在写的，我们的进程睡过去再醒来，把这段时间的事件**补算**回来即可。
 *
 * 代价是要用户单独授予「使用情况访问」（跳系统设置页，一次性）。
 * 没授予时上层会退回无障碍计时（[FocusLockAccessibilityService.tickAppLimit]），两者不会同时记。
 */
object AppLimitWatcher {

    /** 轮询间隔。只决定覆盖层出现的延迟 —— 记账精度由事件时间戳保证，跟轮询频率无关。 */
    const val POLL_MILLIS = 2_000L

    private const val ACCESS_CACHE_MILLIS = 10_000L

    /** 查询窗口往前多要一点：系统的事件入库有延迟，卡着游标查会漏读。 */
    private const val QUERY_OVERLAP_MILLIS = 3_000L

    /** 断档超过这么久就不回溯了（进程长期没跑 / 墙上时间被改），宁可少记不可虚增。 */
    private const val MAX_REPLAY_MILLIS = 3_600_000L

    /** 服务刚起来时看不出前台是谁，往前找这么久内的最后一条记录。 */
    private const val COLD_LOOKBACK_MILLIS = 60_000L

    /** 账已经算到这个时刻（墙上时间，不是单调时钟 —— 事件时间戳是墙上时间）。 */
    private var cursorMs = 0L

    /** [cursorMs] 之后一直在前台的包名；null = 息屏或不确定，不记账。 */
    private var foreground: String? = null

    private var lastTickElapsed = 0L
    private var accessCheckedElapsed = 0L
    private var accessGranted = false

    /**
     * 不足一秒的零头攒着下次一起算。
     *
     * 驱动源不止一个（守护服务 2 秒一次、无障碍接管时 1 秒一次），交错时段长常常不是整秒，
     * 逐段各自截断会把零头全部丢掉 —— 一小时能少记十几分钟。
     */
    private var pendingMillis = 0L

    /**
     * 记账到底有没有在跑。
     *
     * 无障碍那边的 tick 靠它决定要不要接管：两边同时算会把时长翻倍，
     * 而只认一个来源又会在任一方掉线时静默停摆。用「最近一次 tick 的时刻」当心跳，
     * 谁活着谁干，谁死了另一个 6 秒内顶上。
     */
    fun isRunning(): Boolean =
        SystemClock.elapsedRealtime() - lastTickElapsed < POLL_MILLIS * 3

    /** 有没有「使用情况访问」权限。这是特殊权限，只能跳系统设置页让用户自己给。 */
    fun hasUsageAccess(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - accessCheckedElapsed < ACCESS_CACHE_MILLIS) return accessGranted
        accessCheckedElapsed = now
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        accessGranted = appOps != null && runCatching {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return accessGranted
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** 由 [GuardService] 每 [POLL_MILLIS] 调一次。 */
    fun tick(context: Context) {
        if (!hasUsageAccess(context)) return
        lastTickElapsed = SystemClock.elapsedRealtime()

        val now = System.currentTimeMillis()
        val from = cursorMs
        // 第一次跑、墙上时间被往前拨、或者中间断了一大段：不对账，从此刻重新开始。
        // ⚠️ 判定必须是 now < from（严格小于）：两个驱动源可能在同一毫秒各调一次，
        // 用 <= 会把这种正常情况当成「时间被拨回去」，每次都重走 reset、这段时间直接不记账。
        if (from == 0L || now < from || now - from > MAX_REPLAY_MILLIS) {
            reset(context, now)
            return
        }

        var cursor = from
        var packageName = foreground
        for (change in queryChanges(context, from - QUERY_OVERLAP_MILLIS, now)) {
            if (change.at <= cursor) continue
            credit(context, packageName, cursor, change.at)
            cursor = change.at
            packageName = change.foreground
        }
        credit(context, packageName, cursor, now)
        cursorMs = now
        foreground = packageName
        applyOverlay(context, packageName)
    }

    private fun reset(context: Context, now: Long) {
        cursorMs = now
        foreground = queryChanges(context, now - COLD_LOOKBACK_MILLIS, now)
            .lastOrNull()
            ?.foreground
        applyOverlay(context, foreground)
    }

    /** 把 [from, to) 这段时间算到 [packageName] 头上。 */
    private fun credit(context: Context, packageName: String?, from: Long, to: Long) {
        if (packageName == null || to <= from) return
        // 限额层盖着 = 用户被挡在外面，没在玩，不能顺着继续烧额度（否则加时白加）
        if (AppLimitOverlayController.isShowing()) return
        if (LockMachineController.isActive(context)) return
        // 没设限额的应用在这里就被挡掉了，不产生任何写入
        pendingMillis += to - from
        val seconds = pendingMillis / 1_000L
        if (seconds <= 0L) return
        pendingMillis -= seconds * 1_000L
        AppLimitStore.addSeconds(context, packageName, seconds)
    }

    private fun applyOverlay(context: Context, packageName: String?) {
        if (packageName != null &&
            AppLimitStore.isLocked(context, packageName) &&
            !LockMachineController.isActive(context) &&
            !LockOverlayController.isShowing()
        ) {
            AppLimitOverlayController.show(context, packageName)
        } else {
            AppLimitOverlayController.hide(context)
        }
    }

    private class Change(val at: Long, val foreground: String?)

    /**
     * 把使用记录里的「前台应用变化」抽出来，按时间顺序排好。
     * 息屏也要当成一次变化（foreground = null）—— 否则最后那个应用会一直被算下去。
     */
    private fun queryChanges(context: Context, from: Long, to: Long): List<Change> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val events = runCatching { manager.queryEvents(from, to) }.getOrNull() ?: return emptyList()
        val changes = mutableListOf<Change>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // ACTIVITY_RESUMED == 老版本的 MOVE_TO_FOREGROUND，取值相同，minSdk 26 都能用
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val packageName = event.packageName?.toString().orEmpty()
                    if (packageName.isNotEmpty()) changes += Change(event.timeStamp, packageName)
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    changes += Change(event.timeStamp, null)
            }
        }
        return changes
    }
}
