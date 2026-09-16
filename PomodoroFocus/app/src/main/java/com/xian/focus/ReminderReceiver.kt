package com.xian.focus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * 到期提醒的落地点：只负责把闹钟带过来的标题/正文发成一条通知，不碰数据库。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        // 用户关掉了通知权限就静默跳过，别让提醒失败把进程带崩。
        runCatching {
            NotificationManagerCompat.from(context).notify(
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                notification
            )
        }

        // 「每天重复」的提醒（习惯打卡）：响完自己把下一天排上。
        // 纯粹的时间运算，一个字都不用查库，所以这个接收器仍然是「无状态」的。
        if (intent.getBooleanExtra(EXTRA_REPEAT_DAILY, false)) {
            val triggerAt = intent.getLongExtra(EXTRA_TRIGGER_AT, 0L)
            val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)
            if (triggerAt > 0 && requestCode != 0) {
                ReminderScheduler.schedule(
                    context = context,
                    requestCode = requestCode,
                    notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                    title = title,
                    text = text,
                    triggerAt = nextDailyTrigger(triggerAt, System.currentTimeMillis()),
                    repeatDaily = true
                )
            }
        }
    }

    /**
     * 下一天同一时刻。
     *
     * 用 `Calendar.add(DAY_OF_MONTH, 1)` 而不是 `+ 24 小时`：后者在有夏令时的地区会漂一小时。
     * 循环是为了兜住「设备睡了几天、闹钟刚补触发」的情况 —— 那时算出来的时刻可能仍在过去，
     * 直接交给 [ReminderScheduler.schedule] 会被它当成过期闹钟撤销掉，提醒就断了。
     */
    private fun nextDailyTrigger(triggerAt: Long, now: Long): Long {
        var next = triggerAt
        var guard = 0
        while (next <= now && guard++ < 400) {
            next = Calendar.getInstance().apply {
                timeInMillis = next
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
        }
        return next
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
        )
    }

    companion object {
        const val ACTION_REMIND = "com.xian.focus.action.REMIND"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** 排这条闹钟时用的 requestCode —— 每天重复的提醒要靠它把下一天排回同一个槽位。 */
        const val EXTRA_REQUEST_CODE = "request_code"

        /** 见 [ReminderScheduler.schedule] 的 repeatDaily。 */
        const val EXTRA_REPEAT_DAILY = "repeat_daily"

        /** 本条的触发时刻，重排下一天时以它为基准。 */
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** 任务与倒数日共用一条渠道：对用户来说都是「到点提醒」，没必要拆成两个开关。 */
        private const val CHANNEL_ID = "due_reminder"
    }
}
