package com.xian.focus.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xian.focus.MainActivity
import com.xian.focus.R
import com.xian.focus.TimerEngine
import com.xian.focus.TimerState
import com.xian.focus.TimerStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FocusTimerService : LifecycleService() {

    @Inject
    lateinit var timerEngine: TimerEngine

    private var collectJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        collectJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    timerEngine.timerState.collect { state -> handleTimerState(state) }
                }
                launch {
                    timerEngine.focusCompletedEvents.collect { showFocusFinishedNotification() }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val state = timerEngine.timerState.value
        if (state.status == TimerStatus.IDLE) {
            stopSelf()
        } else {
            showTimerInForeground(state)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        super.onDestroy()
    }

    private fun handleTimerState(state: TimerState) {
        if (state.status == TimerStatus.IDLE) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
            return
        }
        if (foregroundStarted) {
            notifyIfAllowed(TIMER_NOTIFICATION_ID, buildTimerNotification(state))
        } else {
            showTimerInForeground(state)
        }
    }

    private fun showTimerInForeground(state: TimerState) {
        val notification = buildTimerNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                TIMER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(TIMER_NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildTimerNotification(state: TimerState) =
        NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("${phaseText(state)} · ${formatSeconds(state.remainingSeconds)}")
            .setContentIntent(buildContentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun showFocusFinishedNotification() {
        val notification = NotificationCompat.Builder(this, FINISH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.focus_finished_title))
            .setContentText(getString(R.string.focus_finished_text))
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notifyIfAllowed(FINISH_NOTIFICATION_ID, notification)
    }

    private fun notifyIfAllowed(notificationId: Int, notification: android.app.Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        }
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun phaseText(state: TimerState): String = when (state.status) {
        TimerStatus.IDLE -> getString(R.string.status_idle)
        TimerStatus.FOCUSING -> getString(R.string.status_focusing)
        TimerStatus.SHORT_BREAK -> getString(R.string.status_short_break)
        TimerStatus.LONG_BREAK -> getString(R.string.status_long_break)
        TimerStatus.PAUSED -> when (state.pausedFromStatus) {
            TimerStatus.SHORT_BREAK -> getString(R.string.status_short_break)
            TimerStatus.LONG_BREAK -> getString(R.string.status_long_break)
            else -> getString(R.string.status_focusing)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val timerChannel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.timer_channel_description)
        }
        val finishChannel = NotificationChannel(
            FINISH_CHANNEL_ID,
            getString(R.string.finish_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.finish_channel_description)
        }
        manager.createNotificationChannels(listOf(timerChannel, finishChannel))
    }

    private fun formatSeconds(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

    companion object {
        private const val TIMER_CHANNEL_ID = "focus_timer"
        private const val FINISH_CHANNEL_ID = "focus_finished"
        private const val TIMER_NOTIFICATION_ID = 1001
        private const val FINISH_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FocusTimerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FocusTimerService::class.java))
        }
    }
}
