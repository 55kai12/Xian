package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import com.haibin.calendarview.Calendar
import com.haibin.calendarview.MonthView
import kotlin.math.min

class CircleMonthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MonthView(context) {

    private val holidayPaint = Paint().apply {
        color = 0xFFE67E22.toInt()
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    override fun onDrawSelected(canvas: Canvas, calendar: Calendar, x: Int, y: Int, hasScheme: Boolean): Boolean {
        canvas.drawCircle(
            x + mItemWidth / 2f,
            y + mItemHeight / 2f,
            min(mItemWidth, mItemHeight) * 0.42f,
            mSelectedPaint
        )
        return true
    }

    override fun onDrawScheme(canvas: Canvas, calendar: Calendar, x: Int, y: Int) = Unit

    override fun onDrawText(
        canvas: Canvas,
        calendar: Calendar,
        x: Int,
        y: Int,
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
        val lunarPaint = when {
            isSelected -> mSelectedLunarTextPaint
            calendar.isCurrentDay -> mCurDayLunarTextPaint
            calendar.isCurrentMonth -> mCurMonthLunarTextPaint
            else -> mOtherMonthLunarTextPaint
        }
        canvas.drawText(calendar.day.toString(), centerX, y + mItemHeight * 0.43f, solarPaint)
        canvas.drawText(calendar.lunar, centerX, y + mItemHeight * 0.70f, lunarPaint)

        // 法定节假日标记：在日期底部画小圆点
        if (HolidayStore.isShowHolidayEnabled(context) &&
            HolidayStore.isHoliday(calendar.month, calendar.day) &&
            calendar.isCurrentMonth
        ) {
            canvas.drawCircle(
                centerX,
                y + mItemHeight * 0.88f,
                min(mItemWidth, mItemHeight) * 0.04f,
                holidayPaint
            )
        }
    }
}
