package com.xian.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 精确闹钟的唯一入口。
 *
 * ⚠️ [AlarmManager.setAlarmClock] **属于「精确闹钟 API」**，Android 12（API 31）起必须持有
 * `SCHEDULE_EXACT_ALARM`，否则抛 SecurityException，报错原文就是
 * 「Caller … needs to hold android.permission.SCHEDULE_EXACT_ALARM or … USE_EXACT_ALARM
 * to call exact alarm APIs」。
 *
 * 「用 setAlarmClock 就不用申请这个权限」是**错的** —— 代码里原先正是这么写的注释，
 * 于是权限没进 Manifest，Android 12+ 上保存带提醒的任务直接失败、保存锁机时段直接闪退。
 * 另外 Android 14 起该权限**不再默认授予**，用户要去系统设置里单独开（见 [openSettings]）。
 *
 * 所以这里只做一件事：**拿得到精确闹钟就用，拿不到就退化成不精确的**。
 * [AlarmManager.setAndAllowWhileIdle] 任何版本都合法、不需要权限，只是可能晚几十秒。
 * 调用方因此永远不会因为权限抛异常 —— 定时锁机和提醒顶多晚一点，不会整个失效。
 */
object ExactAlarms {

    /** Android 12+ 的「闹钟和提醒」设置页。写成字面量，免得为它在低版本上引 @RequiresApi。 */
    private const val ACTION_REQUEST_SCHEDULE_EXACT_ALARM =
        "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"

    fun canUseExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return manager.canScheduleExactAlarms()
    }

    /**
     * 排一个「到点触发」的闹钟。[showIntent] 只被 setAlarmClock 用来在状态栏显示
     * 「即将响铃」并提供返回入口；退化路径用不上它，但两个 PendingIntent 的身份
     * 仍是 (requestCode, action, component)，所以撤销逻辑不受走哪条路影响。
     */
    fun schedule(
        context: Context,
        triggerAt: Long,
        showIntent: PendingIntent,
        operation: PendingIntent
    ) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        if (canUseExact(context)) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    /** 跳到系统「闹钟和提醒」页；个别 ROM 没有这个页面，退到应用详情页让用户自己找。 */
    fun openSettings(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        runCatching { context.startActivity(Intent(ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)) }
            .onFailure {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                }
            }
    }
}
