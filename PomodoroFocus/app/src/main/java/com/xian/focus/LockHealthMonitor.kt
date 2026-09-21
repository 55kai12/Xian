package com.xian.focus

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 锁机「掉线」自检。
 *
 * 锁机与应用限额的计时都挂在无障碍服务上，而系统（尤其国产 ROM 的后台清理）会把它悄悄关掉。
 * 关掉之后应用侧完全无感：设置里的开关还亮着，实际什么都不会发生 ——
 * 用户要等到「今天怎么没锁住」才发现，那时一天已经过去了。
 *
 * 这里用一个半小时一次的闹钟主动体检：发现「在等保护、但无障碍没开」就发一条通知，
 * 引导用户回去重新打开。同一状态只打扰一次，恢复后自动复位。
 */
object LockHealthMonitor {

    private const val PREFS = "lock_health_prefs"
    private const val KEY_WARNED = "warned"
    private const val CHANNEL_ID = "lock_health"
    private const val NOTIFICATION_ID = 9011
    private const val INTERVAL_MILLIS = 30 * 60 * 1000L
    private const val REQ_CHECK = 1101
    const val ACTION_CHECK = "com.xian.focus.action.LOCK_HEALTH_CHECK"

    /** 是否「在等保护」：设过锁机计划、正在锁机、或设过应用限额。 */
    private fun needsProtection(context: Context): Boolean =
        LockMachineScheduler.isEnabled(context) ||
            LockMachineController.isActive(context) ||
            AppLimitStore.limitedPackages(context).isNotEmpty()

    /**
     * 排体检闹钟。用不精确重复：这是提醒、不是定时任务，晚几分钟无所谓；
     * 换来的好处是进程被杀后系统仍会按周期把它唤回来。
     *
     * 已经排过就什么都不做 —— 若每次冷启动都把触发时间往后推 30 分钟，
     * 爱开 App 的人会让这个闹钟永远等不到。
     */
    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val existing = PendingIntent.getBroadcast(
            context, REQ_CHECK, checkIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) return
        alarm.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + INTERVAL_MILLIS,
            INTERVAL_MILLIS,
            pendingIntent(context)
        )
    }

    /** 体检一次。返回当前是否处于「该保护、也在保护」的健康状态。 */
    fun check(context: Context): Boolean {
        val healthy = LockHealth.isAccessibilityOn(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (healthy) {
            if (prefs.getBoolean(KEY_WARNED, false)) {
                prefs.edit().putBoolean(KEY_WARNED, false).apply()
            }
            return true
        }
        // 没在等保护就别打扰（比如用户压根没用锁机）；已经提醒过也不重复提醒。
        if (!needsProtection(context) || prefs.getBoolean(KEY_WARNED, false)) return false
        if (notifyLost(context)) prefs.edit().putBoolean(KEY_WARNED, true).apply()
        return false
    }

    /** @return 通知是否真的发出去了；通知权限被关时返回 false，那就下次再试。 */
    private fun notifyLost(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        ensureChannel(context)

        val reopen = PendingIntent.getActivity(
            context, 0, LockHealth.accessibilityIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = context.getString(R.string.health_lost_message)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setLargeIcon(NotifyIcon.large(context))
            .setContentTitle(context.getString(R.string.health_lost_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(reopen)
            .build()

        return runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.health_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.health_channel_description)
            }
        )
    }

    private fun checkIntent(context: Context) =
        Intent(context, LockHealthCheckReceiver::class.java).setAction(ACTION_CHECK)

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_CHECK, checkIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** 体检闹钟的落地点：跑一次自检。重复闹钟由系统维持，这里不重排。 */
class LockHealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LockHealthMonitor.ACTION_CHECK) return
        LockHealthMonitor.check(context.applicationContext)
    }
}
