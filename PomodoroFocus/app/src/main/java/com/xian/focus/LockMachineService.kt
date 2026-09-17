package com.xian.focus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LockMachineService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopLock()
            return START_NOT_STICKY
        }
        if (!LockMachineController.isActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // 起锁前先自检一次，而不是无脑盖屏：定时到点 / 开机自启 / 保存时段后自检 都会走到这里，
        // 那几条路径手上都没有「用户现在在哪个应用」的信息，而用户此刻很可能就在白名单应用里。
        LockMachineOverlayController.sync(this)
        lifecycleScope.launch(Dispatchers.Main) {
            var sinceNotification = 0L
            while (LockMachineController.isActive(this@LockMachineService)) {
                // 每秒补一次「该不该盖」，但只在层没显示时做。
                //
                // 为什么需要它：盖屏这件事原本完全由无障碍窗口事件驱动，而服务没打开或
                // 被 ROM 清掉之后事件就不来了 —— 那种情况下用户切进白名单应用，锁机层
                // 根本不会让开，人就被卡死在里面。这一秒一次的判定读的是系统使用记录，
                // 不依赖任何服务活着（见 ForegroundApp.resolve）。
                // 层显示着的时候它自己每秒自检，这里不用重复跑。
                if (!LockMachineOverlayController.isShowing()) {
                    LockMachineOverlayController.sync(this@LockMachineService)
                }
                if (sinceNotification >= NOTIFICATION_REFRESH_MILLIS) {
                    updateNotification()
                    sinceNotification = 0L
                }
                delay(TICK_MILLIS)
                sinceNotification += TICK_MILLIS
            }
            finishLock()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun stopLock() {
        if (!LockMachineController.isActive(this)) {
            stopSelf()
            return
        }
        // 主动退出的唯一扣额度点：浮层长按、通知栏、应用内按钮都汇到这里
        if (!LockExitQuota.consume(this)) {
            Toast.makeText(
                this,
                getString(R.string.exit_quota_exhausted, LockExitQuota.MONTHLY_LIMIT),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // 必须在 stop() 清掉开始时刻之前记数
        LockStats.record(this, heldOut = false)
        LockMachineController.stop(this)
        LockMachineOverlayController.hide(this, force = true)
        stopSelf()
    }

    private fun finishLock() {
        // 自然到期 = 忍住没退；而结束时刻已经被清掉，说明是用户主动退出 ——
        // 那条路（stopLock）已经按 heldOut = false 记过一次，别再当成「忍住」补记一遍。
        // 循环退出的原因就是 isActive 变 false，所以这两种情况只能靠 prefs 是否还留着时刻来分。
        if (LockMachineController.endAt(this) > 0L) {
            LockStats.record(this, heldOut = true)
        }
        LockMachineController.stop(this)
        LockMachineOverlayController.hide(this, force = true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 收尾提示：走的是同一个 NONE 频道，所以**看不到**（v2.0.78 起与锁机期间那条一起收起来了）。
        // 保留它是让「锁机结束」这件事在实现上仍有一个明确落点 —— 锁机层消失本身就是给
        // 用户的信号。将来若要让它单独可见，**另开一个可见频道**，别把这个频道的重要性
        // 改回去（那会连带把锁机期间那条也放出来）。
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle(getString(R.string.custom_lock))
                .setContentText(getString(R.string.lock_machine_finished))
                .setOngoing(false)
                .build()
        )
        stopSelf()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, LockMachineService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.lock_machine_notification))
            .setContentText(getString(R.string.lock_machine_running, LockMachineController.remainingText(this)))
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop_lock_machine), stopPendingIntent)
            .build()
    }

    /**
     * 频道重要性 NONE：这条通知**完全不显示**（状态栏无图标、下拉里也没有），
     * 与 [GuardService] 的守护频道同一套做法。
     *
     * 前台服务必须有通知是系统的硬性要求，但没人规定它必须看得见。锁机时用户要的是
     * 「屏幕被占住」，而通知栏里挂一条「锁机中：剩余 12:00」既没用、又反过来在提醒
     * 「还有条路能出去」（那上面还带个「停止锁机」按钮）。收起来之后 `startForeground`
     * 照常成立、服务照样是前台优先级，只是没人看得见它。
     *
     * ⚠️ 重要性创建后**改不了**（用户自己改的还优先于代码），所以换只能换 ID 重建 ——
     * 旧频道顺手删掉，免得在「设置 → 通知」里留一条僵尸。
     * ⚠️ Android 13+ 会自己往通知栏放一条系统的「后台运行的应用」汇总，以及快捷设置里的
     * 「正在运行的应用」入口。那是系统发的，**应用侧删不掉** —— 严格意义上的完全隐身
     * 在系统层面做不到，这里能做到的是「贤自己不发可见通知、状态栏无图标」。
     */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lock_channel_name),
            NotificationManager.IMPORTANCE_NONE
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** 带版本后缀：通知频道的重要性创建后不可改，想换成「完全不显示」只能换 ID 重建。 */
        private const val CHANNEL_ID = "lock_machine_channel_v2"
        private const val LEGACY_CHANNEL_ID = "lock_machine_channel"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_REFRESH_MILLIS = 30_000L

        /** 「该不该盖」的巡检间隔。一秒是跟着时钟刷新走的，再慢用户就明显觉得「卡了一下才让开」。 */
        private const val TICK_MILLIS = 1_000L
        private const val ACTION_STOP = "com.xian.focus.action.STOP_LOCK_MACHINE"
        private const val EXTRA_DURATION_MINUTES = "duration_minutes"

        /**
         * 开始锁机。白名单不从这里传 —— 它是独立设置，覆盖层和放行判断都直接读
         * [LockMachineController.whitelist]，这样任何一条启动路径都不会把用户选好的白名单冲掉。
         */
        fun start(context: Context, durationMinutes: Int) {
            LockMachineController.start(context, durationMinutes)
            val intent = Intent(context, LockMachineService::class.java).apply {
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LockMachineService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun resume(context: Context) {
            if (LockMachineController.isActive(context)) {
                context.startForegroundService(Intent(context, LockMachineService::class.java))
            }
        }
    }
}
