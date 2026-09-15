package com.xian.focus

import android.content.Context
import android.os.SystemClock

/**
 * 进程内共享的「最近确认的前台应用」。
 *
 * 为什么需要它：锁机的起锁路径是**无条件盖屏**的（定时到点、开机自启、保存时段后自检），
 * 那些路径手上没有任何前台应用的信息。而「盖还是不盖」恰恰要问一句
 * 「用户现在正在用的这个应用，是不是在白名单里」—— 答案是「是」就绝不能盖，
 * 否则用户看到的就是「明明加了白名单，还是被锁」。
 *
 * 写入方是 [FocusLockAccessibilityService]（它本来就是前台应用感知中枢），
 * 读取方是 [LockMachineOverlayController]。**但只靠一个源是不够的**：
 * 无障碍没打开或被 ROM 清掉之后这个值永远是 null，而 null 在调用方是按「该锁」处理的 ——
 * 这正是「加了白名单还被锁、还被卡住」的成因。所以 [resolve] 在缓存不可信时
 * 会退回系统使用记录（[UsageForeground]）兜底，那条路不依赖任何服务活着。
 */
object ForegroundApp {

    /**
     * 窗口事件喂进来的值有多久的信任期。
     *
     * 太短会频繁去读使用记录（那是一次 binder 调用加事件遍历），太长则用户真切走了还按旧值放行。
     * 锁机层每秒问一次，八秒足够覆盖「事件偶尔迟到」，又不至于让判断明显滞后。
     */
    private const val TRUST_MILLIS = 8_000L

    /** 最近一次确认的前台应用包名；未知为 null。 */
    @Volatile
    var packageName: String? = null
        private set

    /** [packageName] 被确认的时刻（单调时钟）；0 表示当前值不新鲜。 */
    @Volatile
    private var confirmedAt = 0L

    /** 无障碍服务确认了一次前台变化。传 null 表示「这个值不作数了」，立即失去信任期。 */
    fun mark(packageName: String?) {
        this.packageName = packageName
        confirmedAt = if (packageName == null) 0L else SystemClock.elapsedRealtime()
    }

    /**
     * 现在的真实前台应用。窗口事件的值新鲜就直接用（零成本）；
     * 不新鲜或为空时读一次系统使用记录，那条路也拿不到才退回旧值（可能是 null）。
     *
     * 返回 null 表示**不知道**，调用方应当按「该锁」处理 —— 漏锁比误锁严重得多。
     */
    fun resolve(context: Context): String? {
        val cached = packageName
        if (cached != null && SystemClock.elapsedRealtime() - confirmedAt < TRUST_MILLIS) {
            return cached
        }
        return UsageForeground.lastResumed(context) ?: cached
    }
}
