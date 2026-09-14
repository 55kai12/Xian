package com.xian.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 守护前台服务：只要还有「需要一直盯着」的功能开着（应用限额 / 定时锁机时段），
 * 就常驻一个最低优先级的通知，把进程优先级抬上去。
 *
 * 起因：应用限额的计时和拦截都跑在无障碍服务里，而**用户从最近任务划掉贤之后，
 * 国产 ROM 会顺手清掉进程**（部分直接 force-stop）—— 无障碍服务随之停摆，
 * 限额就再也拦不住，看起来像「删了后台就失效」。
 * 有前台服务的应用不在这类清理的名单里，这是唯一能对抗它的手段。
 *
 * 它只负责活着：计时、判断、拦截都在别的类里。
 */
class GuardService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先无条件转前台：从 startForegroundService 进来却没能及时 startForeground
        // 会直接抛 ForegroundServiceDidNotStartInTimeException。
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!needsGuard(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 被系统回收后自动重建；重建时 intent 为 null，这里会再自检一遍条件
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setContentIntent(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** 最低重要性：不出现在状态栏、不发声、不亮屏，只在通知栏下拉里看得到。 */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "guard_channel"
        private const val NOTIFICATION_ID = 2002

        /** 有没有东西需要一直盯着：应用限额、定时锁机时段。 */
        fun needsGuard(context: Context): Boolean =
            AppLimitStore.limitedPackages(context).isNotEmpty() ||
                LockMachineScheduler.isEnabled(context)

        /**
         * 按当前设置对齐服务状态，幂等 —— 可以在任何「用户正看着应用」的地方反复调。
         *
         * ⚠️ 调用点必须在前台时机（`Activity.onStart`、用户刚点完保存）：
         * Android 12+ 不允许后台启动前台服务，从后台调会被系统直接拒掉。
         */
        fun sync(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (needsGuard(context)) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
