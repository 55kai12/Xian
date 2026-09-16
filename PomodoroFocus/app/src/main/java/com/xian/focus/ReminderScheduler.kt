package com.xian.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 「到点发一条通知」的公共管道，目前有两个使用者：任务到期提醒、倒数日提醒。
 *
 * 精确性交给 [ExactAlarms]：拿到「闹钟和提醒」权限就用精确闹钟，拿不到自动退化成不精确的
 * —— 晚几十秒，但不会抛异常。（原先这里写着「用 setAlarmClock 就不用申请
 * SCHEDULE_EXACT_ALARM」，那是错的：setAlarmClock 本身就是要权限的精确闹钟 API。）
 *
 * 标题 / 通知 id 走 extra 直接带给接收器，接收器因此不需要碰数据库。
 * PendingIntent 的相等性只看 requestCode + action + component，不看 extra，
 * 所以 cancel() 不传标题也能命中同一个闹钟。
 */
object ReminderScheduler {

    /**
     * 排一条提醒。
     *
     * [repeatDaily] 为真时，[ReminderReceiver] 发完通知会自己算下一天并重排 ——
     * 习惯提醒要每天响，而 AlarmManager 没有「每天重复的精确闹钟」这种原语。
     * 重排是纯时间运算，所以接收器仍然不用碰数据库。
     */
    fun schedule(
        context: Context,
        requestCode: Int,
        notificationId: Int,
        title: String,
        text: String,
        triggerAt: Long,
        repeatDaily: Boolean = false
    ) {
        if (triggerAt <= System.currentTimeMillis()) {
            cancel(context, requestCode)
            return
        }
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        ExactAlarms.schedule(
            context, triggerAt, showIntent,
            alarmIntent(context, requestCode, notificationId, title, text, repeatDaily, triggerAt)
        )
    }

    fun cancel(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(alarmIntent(context, requestCode, 0, null, null))
    }

    private fun alarmIntent(
        context: Context,
        requestCode: Int,
        notificationId: Int,
        title: String?,
        text: String?,
        repeatDaily: Boolean = false,
        triggerAt: Long = 0
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMIND)
            .putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(ReminderReceiver.EXTRA_REQUEST_CODE, requestCode)
        // 只有「每天重复」的提醒需要这两项：接收器靠它们算出下一天的时刻。
        // cancel() 不带也能命中同一个闹钟 —— PendingIntent 的相等性只看 requestCode + action + component。
        if (repeatDaily) {
            intent.putExtra(ReminderReceiver.EXTRA_REPEAT_DAILY, true)
            intent.putExtra(ReminderReceiver.EXTRA_TRIGGER_AT, triggerAt)
        }
        if (title != null) intent.putExtra(ReminderReceiver.EXTRA_TITLE, title)
        if (text != null) intent.putExtra(ReminderReceiver.EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
