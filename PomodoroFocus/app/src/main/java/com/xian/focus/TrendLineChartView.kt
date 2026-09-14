package com.xian.focus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.xian.focus.data.DailyCount

class TrendLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val path = Path()
    private var data: List<DailyCount> = emptyList()

    fun setData(data: List<DailyCount>) {
        this.data = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val values = data.ifEmpty { List(7) { DailyCount("", 0) } }
        val hasCompletedTask = values.any { it.count > 0 }
        val max = maxOf(values.maxOf { it.count }, 1)
        val width = width.toFloat()
        val height = height.toFloat()
        val edgeInset = 7f * resources.displayMetrics.density
        val chartHeight = height - paddingTop - paddingBottom - edgeInset * 2
        if (chartHeight <= 0) return
        val gap = width / values.size
        val points = values.mapIndexed { index, daily ->
            val x = index * gap + gap / 2f
            val y = if (hasCompletedTask) {
                paddingTop + edgeInset + (chartHeight - chartHeight * daily.count / max)
            } else {
                paddingTop + edgeInset + chartHeight * 0.68f
            }
            x to y
        }
        path.reset()
        path.moveTo(points.first().first, points.first().second)
        // Catmull-Rom 控制点转三次贝塞尔：曲线平滑地穿过每一天的点。
        // 但两个控制点的 y 必须夹在本段两端之间 —— 否则只有某一天有值时，相邻那两段会被
        // 拉出过冲，画面上看着「周四、周六好像也有值」，用户会以为统计多算了（实际是画多了）。
        for (index in 0 until points.lastIndex) {
            val previous = points.getOrElse(index - 1) { points[index] }
            val start = points[index]
            val end = points[index + 1]
            val next = points.getOrElse(index + 2) { end }
            val low = minOf(start.second, end.second)
            val high = maxOf(start.second, end.second)
            path.cubicTo(
                start.first + (end.first - previous.first) / 6f,
                (start.second + (end.second - previous.second) / 6f).coerceIn(low, high),
                end.first - (next.first - start.first) / 6f,
                (end.second - (next.second - start.second) / 6f).coerceIn(low, high),
                end.first,
                end.second
            )
        }
        canvas.drawPath(path, linePaint)
        points.forEachIndexed { index, point ->
            canvas.drawCircle(point.first, point.second, 3f * resources.displayMetrics.density, dotPaint)
        }
    }
}
