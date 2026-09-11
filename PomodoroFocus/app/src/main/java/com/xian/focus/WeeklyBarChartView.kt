package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.xian.focus.data.DailyCount

class WeeklyBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint().apply {
        color = Color.parseColor("#C9A961")
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = -0x1000000
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val valuePaint = Paint().apply {
        color = -0x1000000
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private var data: List<DailyCount> = emptyList()

    fun setData(data: List<DailyCount>) {
        this.data = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return
        val max = maxOf(data.maxOf { it.count }, 1)
        val width = width.toFloat()
        val height = height.toFloat()
        val labelHeight = 56f
        val valueHeight = 36f
        val chartHeight = height - labelHeight - valueHeight - paddingTop - paddingBottom
        if (chartHeight <= 0) return
        val gap = width / data.size
        val barWidth = gap * 0.55f
        data.forEachIndexed { index, daily ->
            val left = index * gap + (gap - barWidth) / 2f
            val barHeight = chartHeight * daily.count / max
            val top = valueHeight + paddingTop + (chartHeight - barHeight)
            canvas.drawRect(left, top, left + barWidth, top + barHeight, barPaint)
            canvas.drawText(daily.count.toString(), left + barWidth / 2f, top - 8f, valuePaint)
            canvas.drawText(daily.dayLabel, left + barWidth / 2f, height - 16f, textPaint)
        }
    }
}
