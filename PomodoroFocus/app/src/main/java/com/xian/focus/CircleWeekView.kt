package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.haibin.calendarview.Calendar
import com.haibin.calendarview.WeekView
import kotlin.math.min

class CircleWeekView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WeekView(context) {

    private val holidayPaint = Paint().apply {
        color = 0xFFE67E22.toInt()
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    override fun onDrawSelected(canvas: Canvas, calendar: Calendar, x: Int, hasScheme: Boolean): Boolean {
        canvas.drawCircle(
            x + mItemWidth / 2f,
            mItemHeight / 2f,
            min(mItemWidth, mItemHeight) * 0.34f,
            mSelectedPaint
        )
        return true
    }

    override fun onDrawScheme(canvas: Canvas, calendar: Calendar, x: Int) = Unit

    override fun onDrawText(
        canvas: Canvas,
        calendar: Calendar,
        x: Int,
        hasScheme: Boolean,
        isSelected: Boolean
    ) {
        val centerX = x + mItemWidth / 2f
        val solarPaint = when {
            isSelected -> mSelectTextPaint
            calendar.isCurrentDay -> mCurDayTextPaint
            calendar.isCurrentMonth -> mCurMonthTextPaint
            else -> mOtherMonthTextPaint
        }
        canvas.drawText(calendar.day.toString(), centerX, mItemHeight * 0.58f, solarPaint)

        // 法定节假日标记：在日期底部画小圆点
        if (HolidayStore.isShowHolidayEnabled(context) &&
            HolidayStore.isHoliday(calendar.month, calendar.day)
        ) {
            canvas.drawCircle(
                centerX,
                mItemHeight * 0.82f,
                min(mItemWidth, mItemHeight) * 0.05f,
                holidayPaint
            )
        }
    }
}
