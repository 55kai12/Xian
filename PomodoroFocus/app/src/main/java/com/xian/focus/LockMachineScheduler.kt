package com.xian.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * 定时锁机：支持任意多个时段，每个时段各自排「开始」「结束」两个闹钟。
 *
 * 时段用 `start-end`（一天中的第几分钟）表示，跨日直接用 end <= start 表达：
 * `23:00 → 07:00` 存成 `1380-420`，时长按 (end - start + 1440) % 1440 算 = 480 分钟。
 * 这样「跨日」不需要额外字段，也不会出现「结束时间必须大于开始时间」的假限制。
 */
object LockMachineScheduler {
    private const val PREFS_NAME = "lock_schedule_prefs"
    private const val KEY_SLOTS = "slots"

    // 旧版只支持单时段，这三个键仅用于升级后的一次性迁移。
    private const val KEY_LEGACY_ENABLED = "enabled"
    private const val KEY_LEGACY_START = "start_minute"
    private const val KEY_LEGACY_END = "end_minute"

    const val ACTION_START = "com.xian.focus.action.LOCK_START"
    const val ACTION_END = "com.xian.focus.action.LOCK_END"
    const val EXTRA_SLOT_START = "com.xian.focus.extra.SLOT_START"
    const val EXTRA_SLOT_END = "com.xian.focus.extra.SLOT_END"

    const val MINUTES_PER_DAY = 24 * 60

    /** 时段上限。纯粹是给闹钟 requestCode 划一个安全区间，8 个对个人使用绰绰有余。 */
    const val MAX_SLOTS = 8

    private const val REQ_START_BASE = 1001
    private const val REQ_END_BASE = 1101

    /** 重排闹钟时对「当前时刻」留出的安全余量，避免同一秒内反复触发。 */
    private const val RESCHEDULE_GUARD_MILLIS = 1_000L

    data class Slot(val startMinute: Int, val endMinute: Int) {
        /** 时长（分钟）；end <= start 表示跨日。 */
        val durationMinutes: Int
            get() = ((endMinute - startMinute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

        /** 结束时刻落在第二天（或更晚），例如 23:00 → 07:00。 */
        val crossesMidnight: Boolean
            get() = endMinute <= startMinute

        /** 起止完全相同 = 0 时长，是无效时段。 */
        val valid: Boolean
            get() = startMinute in 0 until MINUTES_PER_DAY &&
                endMinute in 0 until MINUTES_PER_DAY &&
                durationMinutes > 0
    }

    fun slots(context: Context): List<Slot> {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_SLOTS, null)
        if (raw != null) return decode(raw)
        // 升级路径：旧版只存了一个 start/end，第一次读到就把它转成时段列表写回。
        if (prefs.getBoolean(KEY_LEGACY_ENABLED, false)) {
            val migrated = listOf(
                Slot(prefs.getInt(KEY_LEGACY_START, 0), prefs.getInt(KEY_LEGACY_END, 0))
            ).filter { it.valid }
            prefs.edit()
                .putString(KEY_SLOTS, encode(migrated))
                .remove(KEY_LEGACY_ENABLED)
                .remove(KEY_LEGACY_START)
                .remove(KEY_LEGACY_END)
                .apply()
            return migrated
        }
        return emptyList()
    }

    fun isEnabled(context: Context): Boolean = slots(context).isNotEmpty()

    fun schedule(context: Context, slots: List<Slot>) {
        val sanitized = slots.filter { it.valid }.take(MAX_SLOTS)
        prefs(context).edit()
            .putString(KEY_SLOTS, encode(sanitized))
            .remove(KEY_LEGACY_ENABLED)
            .remove(KEY_LEGACY_START)
            .remove(KEY_LEGACY_END)
            .apply()
        applyAlarms(context)
    }

    fun cancel(context: Context) {
        prefs(context).edit()
            .remove(KEY_SLOTS)
            .remove(KEY_LEGACY_ENABLED)
            .remove(KEY_LEGACY_START)
            .remove(KEY_LEGACY_END)
            .apply()
        cancelAllAlarms(context)
    }

    fun applyAlarms(context: Context) {
        cancelAllAlarms(context)
        val list = slots(context).filter { it.valid }.take(MAX_SLOTS)
        if (list.isEmpty()) return
        val alarmManager = alarmManager(context)
        val showIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        list.forEachIndexed { index, slot ->
            val startAt = nextStartAt(slot, System.currentTimeMillis() + RESCHEDULE_GUARD_MILLIS)
            val endAt = startAt + slot.durationMinutes * 60_000L
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(startAt, showIntent),
                startPendingIntent(context, index, slot)
            )
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(endAt, showIntent),
                endPendingIntent(context, index)
            )
        }
    }

    /**
     * 自检：此刻若正落在某个已启用时段里，立刻开始锁机（剩余时长按剩余时段算）。
     *
     * 这是「保存即生效」的关键一步。`applyAlarms` 只负责排下一次，而它会把已经过去的
     * 开始时刻顺延到明天 —— 用户 23:26 保存一个 23:23 开始的时段，就会干等一天，
     * 看起来就像「设了不生效」。
     *
     * @return 命中的时段；没命中（或锁机已在跑）返回 null。
     */
    fun resumeIfInScheduledWindow(context: Context): Slot? {
        if (LockMachineController.isActive(context)) return null
        val slot = currentSlot(context) ?: return null
        val remaining = remainingMinutes(context, slot) ?: return null
        if (remaining <= 0) return null
        LockMachineService.start(context, remaining)
        return slot
    }

    /** 此刻正处在哪个已启用时段里；不在任何时段里返回 null。 */
    fun currentSlot(context: Context): Slot? = slots(context).firstOrNull { slot ->
        remainingMinutes(context, slot) != null
    }

    /** 时段内还剩多少分钟；不在这个时段里返回 null。 */
    fun remainingMinutes(context: Context, slot: Slot): Int? {
        if (!slot.valid) return null
        val now = minuteOfDayNow()
        val elapsed = ((now - slot.startMinute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return if (elapsed < slot.durationMinutes) slot.durationMinutes - elapsed else null
    }

    /** 下一次进入锁机时段的绝对时刻；没设时段返回 null。 */
    fun nextStartAt(context: Context): Long? = slots(context)
        .filter { it.valid }
        .map { nextStartAt(it, System.currentTimeMillis()) }
        .minOrNull()

    /**
     * 某个时段的下一次开始时刻：今天这个点还没到就用今天，已过就顺延到明天。
     * [after] 之后留了 1 秒余量，避免闹钟触发后立刻重排时落回同一秒形成死循环。
     */
    private fun nextStartAt(slot: Slot, after: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, slot.startMinute / 60)
            set(Calendar.MINUTE, slot.startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= after) calendar.add(Calendar.DAY_OF_MONTH, 1)
        return calendar.timeInMillis
    }

    private fun minuteOfDayNow(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }

    private fun encode(slots: List<Slot>): String =
        slots.joinToString(";") { "${it.startMinute}-${it.endMinute}" }

    private fun decode(raw: String): List<Slot> = raw.split(';').mapNotNull { part ->
        val pieces = part.trim().split('-')
        if (pieces.size != 2) return@mapNotNull null
        val start = pieces[0].toIntOrNull() ?: return@mapNotNull null
        val end = pieces[1].toIntOrNull() ?: return@mapNotNull null
        Slot(start, end).takeIf { it.valid }
    }

    private fun cancelAllAlarms(context: Context) {
        val alarmManager = alarmManager(context)
        for (index in 0 until MAX_SLOTS) {
            cancelPendingIntent(alarmManager, context, REQ_START_BASE + index, ACTION_START)
            cancelPendingIntent(alarmManager, context, REQ_END_BASE + index, ACTION_END)
        }
    }

    // PendingIntent 的身份只看 (requestCode, action, component)，不看 extras，
    // 所以撤销时不需要带时段参数也能命中同一个。
    private fun cancelPendingIntent(
        alarmManager: AlarmManager,
        context: Context,
        requestCode: Int,
        action: String
    ) {
        val intent = Intent(context, LockAlarmReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pendingIntent)
    }

    private fun startPendingIntent(context: Context, index: Int, slot: Slot): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_START_BASE + index,
            Intent(context, LockAlarmReceiver::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SLOT_START, slot.startMinute)
                .putExtra(EXTRA_SLOT_END, slot.endMinute),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun endPendingIntent(context: Context, index: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQ_END_BASE + index,
            Intent(context, LockAlarmReceiver::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun alarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
