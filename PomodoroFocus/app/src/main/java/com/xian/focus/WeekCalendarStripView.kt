package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import java.util.Calendar

class WeekCalendarStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(26f)
    }
    private val currentDatePaint = Paint(datePaint).apply { color = Color.WHITE }
    private val selectedDatePaint = Paint(datePaint).apply {
        color = ContextCompat.getColor(context, R.color.calendar_chip_text)
    }
    private val selectedCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.calendar_chip)
    }

    private val ringBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        color = ContextCompat.getColor(context, R.color.calendar_scrim)
    }
    private val ringForegroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.calendar_gold_light)
    }
    private val ringRect = RectF()

    private var weekStartMillis = 0L
    private var selectedDateMillis = 0L

    /** key: dayMillis, value: 0f~1f 完成进度 */
    var progressMap: Map<Long, Float> = emptyMap()

    var onDateClick: ((Long) -> Unit)? = null

    fun setWeek(weekStart: Long, selectedDate: Long) {
        weekStartMillis = weekStart
        selectedDateMillis = selectedDate
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weekStartMillis == 0L) return
        val columnWidth = width / 7f
        val centerY = height / 2f
        // 圆的尺寸必须按列宽算，不能写死 dp：360dp 宽的手机把两侧留白扣掉后列宽只剩 ~47dp，
        // 而选中环 dp(29) 的直径是 58dp —— 相邻两个圆直接叠在一起，最右边那个还会被裁掉。
        // 这里把半径压到列宽以内（留约 12% 的间隙），大屏上仍然取原来的尺寸。
        val maxOuterRadius = columnWidth * 0.44f
        val ringRadius = minOf(dp(22f), maxOuterRadius * 0.78f)
        val selectedRingRadius = minOf(dp(29f), maxOuterRadius)
        val selectedRadius = minOf(dp(21f), selectedRingRadius * 0.72f)
        repeat(7) { index ->
            val dayMillis = weekStartMillis + index * DAY_MILLIS
            val centerX = columnWidth * (index + 0.5f)
            val calendar = Calendar.getInstance().apply { timeInMillis = dayMillis }
            val isSelected = dayMillis == selectedDateMillis
            val isToday = dayMillis == startOfToday()

            // 画进度圆环（有任务的日期，选中和非选中都画）
            val progress = progressMap[dayMillis] ?: 0f
            if (progress > 0f) {
                val rRadius = if (isSelected) selectedRingRadius else ringRadius
                ringRect.set(
                    centerX - rRadius,
                    centerY - rRadius,
                    centerX + rRadius,
                    centerY + rRadius
                )
                canvas.drawCircle(centerX, centerY, rRadius, ringBackgroundPaint)
                canvas.drawArc(ringRect, -90f, progress * 360f, false, ringForegroundPaint)
            }

            if (isSelected) canvas.drawCircle(centerX, centerY, selectedRadius, selectedCirclePaint)
            canvas.drawText(
                calendar.get(Calendar.DAY_OF_MONTH).toString(),
                centerX,
                centerY - (datePaint.ascent() + datePaint.descent()) / 2f,
                when {
                    isSelected -> selectedDatePaint
                    isToday -> currentDatePaint
                    else -> datePaint
                }
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && weekStartMillis != 0L) {
            val index = (event.x / (width / 7f)).toInt().coerceIn(0, 6)
            onDateClick?.invoke(weekStartMillis + index * DAY_MILLIS)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
