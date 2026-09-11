package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import java.util.Locale

class MonthCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val textPaint = Paint().apply {
        color = -0x1000000
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val headerPaint = Paint().apply {
        color = Color.parseColor("#8A7A60")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val todayPaint = Paint().apply {
        color = Color.parseColor("#C9A961")
        isAntiAlias = true
    }
    private val selectedPaint = Paint().apply {
        color = Color.parseColor("#3B5B4E")
        isAntiAlias = true
    }
    private val selectedTextPaint = Paint().apply {
        color = -0x1
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val otherMonthPaint = Paint().apply {
        color = Color.parseColor("#C0B8A8")
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val weekdayLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
    private var monthStartMillis = 0L
    private var selectedDate: Long? = null
    private val todayMillis = startOfDay(System.currentTimeMillis())
    private var cells = listOf<Long>()

    var onDateSelected: ((Long) -> Unit)? = null

    fun setMonth(dayInMonth: Long) {
        val cal = Calendar.getInstance().apply { timeInMillis = dayInMonth }
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        monthStartMillis = cal.timeInMillis
        buildCells()
        invalidate()
    }

    fun setSelectedDate(dayMillis: Long?) {
        selectedDate = dayMillis
        invalidate()
    }

    private fun buildCells() {
        val firstDay = Calendar.getInstance().apply { timeInMillis = monthStartMillis }
        val dayOfWeek = firstDay.get(Calendar.DAY_OF_WEEK)
        val offset = (dayOfWeek - Calendar.MONDAY + 7) % 7
        firstDay.add(Calendar.DAY_OF_MONTH, -offset)
        val list = ArrayList<Long>(42)
        for (i in 0 until 42) {
            list.add(firstDay.timeInMillis)
            firstDay.add(Calendar.DAY_OF_MONTH, 1)
        }
        cells = list
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cells.isEmpty()) return
        val width = width.toFloat()
        val height = height.toFloat()
        val headerHeight = 44f
        val cellWidth = width / 7f
        val cellHeight = (height - headerHeight) / 6f
        for (i in 0 until 7) {
            canvas.drawText(weekdayLabels[i], i * cellWidth + cellWidth / 2f, headerHeight - 12f, headerPaint)
        }
        val monthCal = Calendar.getInstance().apply { timeInMillis = monthStartMillis }
        val currentMonth = monthCal.get(Calendar.MONTH)
        cells.forEachIndexed { index, dayMillis ->
            val row = index / 7
            val col = index % 7
            val cx = col * cellWidth + cellWidth / 2f
            val cy = headerHeight + row * cellHeight + cellHeight / 2f
            val cal = Calendar.getInstance().apply { timeInMillis = dayMillis }
            val inMonth = cal.get(Calendar.MONTH) == currentMonth
            val isToday = dayMillis == todayMillis
            val isSelected = dayMillis == selectedDate
            if (isSelected) {
                canvas.drawCircle(cx, cy, cellHeight * 0.38f, selectedPaint)
                canvas.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), cx, cy + 10f, selectedTextPaint)
            } else {
                if (isToday) {
                    canvas.drawCircle(cx, cy, cellHeight * 0.38f, todayPaint)
                    canvas.drawText(cal.get(Calendar.DAY_OF_MONTH).toString(), cx, cy + 10f, selectedTextPaint)
                } else {
                    canvas.drawText(
                        cal.get(Calendar.DAY_OF_MONTH).toString(),
                        cx, cy + 10f,
                        if (inMonth) textPaint else otherMonthPaint
                    )
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val width = width.toFloat()
            val height = height.toFloat()
            val headerHeight = 44f
            val cellWidth = width / 7f
            val cellHeight = (height - headerHeight) / 6f
            val col = (event.x / cellWidth).toInt().coerceIn(0, 6)
            val row = ((event.y - headerHeight) / cellHeight).toInt().coerceIn(0, 5)
            val index = row * 7 + col
            if (index in cells.indices) {
                selectedDate = cells[index]
                invalidate()
                onDateSelected?.invoke(cells[index])
            }
            return true
        }
        return true
    }

    private fun startOfDay(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
