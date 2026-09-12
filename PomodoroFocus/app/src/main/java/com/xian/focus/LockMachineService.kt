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
        LockMachineOverlayController.show(this)
        lifecycleScope.launch(Dispatchers.Main) {
            while (LockMachineController.isActive(this@LockMachineService)) {
                updateNotification()
                delay(NOTIFICATION_REFRESH_MILLIS)
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
        LockMachineOverlayController.hide(this)
        stopSelf()
    }

    private fun finishLock() {
        // 自然到期 = 忍住没退；同样要在 stop() 之前记
        LockStats.record(this, heldOut = true)
        LockMachineController.stop(this)
        LockMachineOverlayController.hide(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.custom_lock),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "lock_machine_channel"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_REFRESH_MILLIS = 30_000L
        private const val ACTION_STOP = "com.xian.focus.action.STOP_LOCK_MACHINE"
        private const val EXTRA_DURATION_MINUTES = "duration_minutes"
        private const val EXTRA_WHITELIST = "whitelist"

        fun start(context: Context, durationMinutes: Int, whitelist: Set<String>) {
            LockMachineController.start(context, durationMinutes, whitelist)
            val intent = Intent(context, LockMachineService::class.java).apply {
                putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
                putStringArrayListExtra(EXTRA_WHITELIST, ArrayList(whitelist))
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
