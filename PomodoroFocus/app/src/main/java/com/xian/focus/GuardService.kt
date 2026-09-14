package com.xian.focus

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 守护前台服务：只要还有「需要一直盯着」的功能开着（应用限额 / 定时锁机时段），
 * 就常驻一个最低优先级的通知，把进程优先级抬上去。
 *
 * 起因：应用限额的计时和拦截原先跑在无障碍服务里，而**用户从最近任务划掉贤之后，
 * 国产 ROM 会顺手清掉进程**（部分直接 force-stop）—— 服务随之停摆，限额就再也拦不住，
 * 看起来像「删了后台就失效」。有前台服务的应用不在这类清理的名单里。
 *
 * 但前台服务本身也会被清，所以这里还排了「自己把自己拉回来」的闹钟：
 * 被划掉、被回收之后一两秒内自动重启，用户察觉不到中断。
 * 真被 force-stop 时闹钟会被一并清空，那时应用侧无解（Android 的设计，任何应用都做不到），
 * 只能靠系统的自启动白名单 + 锁定后台 —— 页面里给了引导。
 *
 * 顺带承担限额记账：前台应用从系统使用记录读（见 [AppLimitWatcher]），
 * 本服务活着时由它驱动，被冻结或掉线时无障碍那边的 tick 会自动接管。
 */
class GuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val limitTicker = object : Runnable {
        override fun run() {
            // 兜住异常：tick 里崩一下，下面这行 postDelayed 就不会执行，记账从此永久停摆
            runCatching { AppLimitWatcher.tick(applicationContext) }
            handler.postDelayed(this, AppLimitWatcher.POLL_MILLIS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先无条件转前台：从 startForegroundService 进来却没能及时 startForeground
        // 会直接抛 ForegroundServiceDidNotStartInTimeException。
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!needsGuard(this)) {
            // 用户主动关掉了所有需要守护的功能：收工，并且别再被自己排的闹钟叫醒
            cancelRestart(this)
            handler.removeCallbacks(limitTicker)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 只为定时锁机常驻时不必记账（限额列表是空的，记了也没用）
        handler.removeCallbacks(limitTicker)
        if (AppLimitStore.limitedPackages(this).isNotEmpty()) {
            handler.post(limitTicker)
        }
        // 周期自检：进程被系统按内存压力回收时 START_STICKY 在国产 ROM 上经常不生效，
        // 用一个不精确闹钟兜底（不需要「精确闹钟」权限，doze 下也保证能投递）。
        scheduleRestart(this, SELF_CHECK_MILLIS)
        // 被系统回收后自动重建；重建时 intent 为 null，这里会再自检一遍条件
        return START_STICKY
    }

    /** 从最近任务划掉贤：趁进程还在，赶紧排个闹钟把自己叫回来。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (needsGuard(this)) scheduleRestart(this, RESTART_DELAY_MILLIS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(limitTicker)
        // 到这里有两种情况：用户主动关掉了功能（needsGuard = false，不该复活），
        // 或进程被系统回收（needsGuard 仍成立）—— 后者必须把自己拉回来。
        if (needsGuard(this)) scheduleRestart(this, RESTART_DELAY_MILLIS)
        super.onDestroy()
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
        private const val REQ_RESTART = 2003

        /** 划掉后台后多久复活：短到用户察觉不到，又避开来回抢启动。 */
        private const val RESTART_DELAY_MILLIS = 1_500L

        /** 兜底自检：服务活着时顺延下一次，被回收时负责复活。 */
        private const val SELF_CHECK_MILLIS = 15 * 60 * 1000L

        /** 有没有东西需要一直盯着：应用限额、定时锁机时段。 */
        fun needsGuard(context: Context): Boolean =
            AppLimitStore.limitedPackages(context).isNotEmpty() ||
                LockMachineScheduler.isEnabled(context)

        /**
         * 按当前设置对齐服务状态，幂等 —— 可以在任何「用户正看着应用」的地方反复调。
         *
         * ⚠️ 调用点必须在前台时机（`Activity.onStart`、用户刚点完保存）：
         * Android 12+ 不允许后台启动前台服务，从后台调会被系统直接拒掉。
         * 例外是开机与覆盖安装广播，那两个动作在系统的豁免名单里。
         */
        fun sync(context: Context) {
            val intent = Intent(context, GuardService::class.java)
            if (needsGuard(context)) {
                runCatching { context.startForegroundService(intent) }
            } else {
                cancelRestart(context)
                context.stopService(intent)
            }
        }

        /**
         * 拉起自己的闹钟。用 [AlarmManager.setAndAllowWhileIdle] 而不是精确闹钟 ——
         * 后者在 Android 12+ 要用户单独授予「闹钟和提醒」权限，不值得为一个自检去要。
         * 不精确只是延迟几秒到几十秒，而记账是游标回溯的，晚到的这段时间照样补得回来。
         */
        private fun restartIntent(context: Context): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                REQ_RESTART,
                Intent(context, GuardService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        private fun scheduleRestart(context: Context, delayMillis: Long) {
            runCatching {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMillis,
                    restartIntent(context)
                )
            }
        }

        private fun cancelRestart(context: Context) {
            runCatching {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                manager.cancel(restartIntent(context))
            }
        }
    }
}
