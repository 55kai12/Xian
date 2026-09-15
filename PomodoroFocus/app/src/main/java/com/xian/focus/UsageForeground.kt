package com.xian.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 「用户现在到底在哪个应用」的第二数据源：系统使用记录。
 *
 * 为什么需要第二个源 —— 主源是无障碍服务的窗口事件，它更实时，但**不可靠**：
 * 开关没打开、被系统重启后没接上、被 ROM 清掉之后，事件就再也不来了。
 * 而锁机的放行判断全靠「当前前台是谁」这一条，源一断白名单就形同虚设 ——
 * 起锁时判断不出你在白名单应用里，于是照盖；盖上之后又没有任何事件能让它让开，
 * 用户就被**彻底卡住**，只能长按退出（还要冷静期 + 密码 + 月度额度）。
 *
 * 使用记录是系统自己记的，只要授予了「使用情况访问」就能读，不依赖任何服务活着。
 * 它比窗口事件粗（只记 Activity 的 resumed，不区分同一个应用内的页面切换），
 * 但对「这个包名在白名单里吗」这个问题绰绰有余。
 */
object UsageForeground {

    /**
     * 首次查询往前找多久。
     *
     * 用户可能在一整个下午都待在同一个应用里没切换过 —— 那种情况下「最后一次前台变化」
     * 就是几小时前，窗口开小了会查不到、误判成「不知道」。窗口开大只是一次性的成本，
     * 之后靠 [cursor] 增量推进，每秒只查新发生的那几条。
     */
    private const val INITIAL_LOOKBACK_MILLIS = 12L * 60L * 60L * 1000L

    /**
     * 每次查询往前多要几秒。
     *
     * 使用记录不是实时写入的，刚发生的事件可能过一两秒才出现在查询结果里。
     * 严格从上次查询时刻往后要，那几秒的空档就永远补不回来了。
     */
    private const val WINDOW_OVERLAP_MILLIS = 5_000L

    /** 已经处理到哪个墙上时刻；0 表示还没查过。 */
    private var cursor = 0L

    /** 最近一次「前台应用变化」指向的包名。 */
    private var last: String? = null

    /** 屏幕是否已经关上。息屏时没有前台应用，最后那个不能一直算数。 */
    private var screenOff = false

    /**
     * 读出当前的前台应用包名；读不到为 null（没授权 / 系统刚重启还没记录）。
     *
     * 加的锁不是防查询撞车，而是防 [cursor] 被两条线程同时推进后跳掉中间的事件。
     */
    @Synchronized
    fun lastResumed(context: Context): String? {
        val manager = context.applicationContext
            .getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return current()
        val now = System.currentTimeMillis()
        val from = if (cursor == 0L) {
            now - INITIAL_LOOKBACK_MILLIS
        } else {
            cursor - WINDOW_OVERLAP_MILLIS
        }
        val events = runCatching { manager.queryEvents(from, now) }.getOrNull() ?: return current()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                // ACTIVITY_RESUMED 就是老版本的 MOVE_TO_FOREGROUND，取值相同，minSdk 26 都能用
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    event.packageName?.toString()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            last = it
                            screenOff = false
                        }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOff = true
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOff = false
            }
        }
        cursor = now
        return current()
    }

    /**
     * 息屏期间返回 null，但**不清掉** [last]：亮屏解锁后用户往往还停在息屏前那个应用里，
     * 这时系统不一定补发一条 resumed（他没换过应用），清掉了就会误判成「不知道」，
     * 而「不知道」在锁机那边是按「该盖」处理的。
     */
    private fun current(): String? = if (screenOff) null else last
}
