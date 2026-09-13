package com.xian.focus

import android.content.Context
import java.util.Calendar
import java.util.Locale

/**
 * 「今天 07:00 / 明天 07:00 / 后天 07:00 / 09-15 07:00」这类相对日期文案。
 *
 * 锁机时段可以跨日（23:00 → 次日 07:00），这种场景下「07:00」到底是今天早上还是明天早上
 * 光看时间根本分不清 —— 锁机页、覆盖层、保存后的提示都得用同一套说法，所以收在一处，
 * 别各写一份，否则某处漏了「明天」就会让用户以为锁机设错了。
 */
internal object TimeLabels {

    /** 把「一天中的第几分钟」（0..1439）格式化成 HH:mm。 */
    fun clock(minuteOfDay: Int): String {
        val normalized = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return String.format(Locale.getDefault(), "%02d:%02d", normalized / 60, normalized % 60)
    }

    /** 相对今天的说法：今天 / 明天 / 后天 HH:mm，再远就用 MM-dd HH:mm。 */
    fun relative(context: Context, atMillis: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = atMillis }
        val clock = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            target.get(Calendar.HOUR_OF_DAY),
            target.get(Calendar.MINUTE)
        )
        return when (dayOffset(target)) {
            0 -> context.getString(R.string.time_today_format, clock)
            1 -> context.getString(R.string.time_tomorrow_format, clock)
            2 -> context.getString(R.string.time_day_after_format, clock)
            else -> String.format(
                Locale.getDefault(),
                "%02d-%02d %s",
                target.get(Calendar.MONTH) + 1,
                target.get(Calendar.DAY_OF_MONTH),
                clock
            )
        }
    }

    /** 目标日期相对今天是第几天。两边都归零到当天 0 点再比，避免时/分干扰。 */
    private fun dayOffset(target: Calendar): Int {
        val today = startOfDay(Calendar.getInstance())
        val other = startOfDay(Calendar.getInstance().apply { timeInMillis = target.timeInMillis })
        return ((other - today) / MILLIS_PER_DAY).toInt()
    }

    private fun startOfDay(calendar: Calendar): Long = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val MINUTES_PER_DAY = 24 * 60
    private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
}
