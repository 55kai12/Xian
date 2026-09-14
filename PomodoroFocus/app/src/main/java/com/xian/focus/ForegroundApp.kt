package com.xian.focus

/**
 * 进程内共享的「最近一次确认的前台应用」。
 *
 * 为什么需要它：锁机的起锁路径是**无条件盖屏**的（定时到点、开机自启、保存时段后自检），
 * 那些路径手上没有任何前台应用的信息。而「盖还是不盖」恰恰要问一句
 * 「用户现在正在用的这个应用，是不是在白名单里」—— 答案是「是」就绝不能盖，
 * 否则用户看到的就是「明明加了白名单，还是被锁」。
 *
 * 写入方只有 [FocusLockAccessibilityService]（它本来就是前台应用感知中枢），
 * 读取方目前是 [LockMachineOverlayController.sync]。
 */
object ForegroundApp {

    /** 最近一次确认的前台应用包名；未知为 null（这种情况下按「该锁」处理）。 */
    @Volatile
    var packageName: String? = null
}
