package com.xian.focus

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.xian.focus.data.DailyCount

/**
 * 统一配置古风主题的 MPAndroidChart。
 */
object XianCharts {

    private const val COLOR_PRIMARY = "#3B5B4E"
    private const val COLOR_GOLD = "#C9A961"
    private const val COLOR_GOLD_LIGHT = "#E8D5A3"
    private const val COLOR_GRID = "#E0D6C2"
    private const val COLOR_TEXT = "#5A4E3A"

    fun setBarData(chart: BarChart, data: List<DailyCount>) {
        applyBarStyle(chart, data.map { it.dayLabel })
        val entries = data.mapIndexed { index, item ->
            BarEntry(index.toFloat(), item.count.toFloat())
        }
        val set = BarDataSet(entries, "").apply {
            color = Color.parseColor(COLOR_GOLD)
            valueTextColor = Color.parseColor(COLOR_TEXT)
            valueTextSize = 11f
            setDrawValues(true)
            barShadowColor = Color.parseColor("#F0E8D6")
        }
        chart.data = BarData(set).apply {
            barWidth = 0.45f
        }
        chart.animateY(700)
        chart.invalidate()
    }

    fun setLineData(chart: LineChart, data: List<DailyCount>) {
        applyLineStyle(chart, data.map { it.dayLabel })
        val entries = data.mapIndexed { index, item ->
            Entry(index.toFloat(), item.count.toFloat())
        }
        val set = LineDataSet(entries, "").apply {
            color = Color.WHITE
            lineWidth = 2.5f
            setCircleColor(Color.parseColor("#FFFFFF"))
            circleRadius = 4f
            setDrawCircleHole(false)
            valueTextColor = Color.parseColor("#FFFFFF")
            valueTextSize = 10f
            setDrawValues(false)
            setDrawFilled(false)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
        }
        chart.data = LineData(set)
        chart.setViewPortOffsets(0f, 10f, 0f, 0f)
        chart.xAxis.axisMinimum = -0.5f
        chart.xAxis.axisMaximum = (data.size - 1).toFloat() + 0.5f
        chart.setExtraOffsets(0f, 0f, 0f, 0f)
        chart.animateX(500)
        chart.invalidate()
    }

    private fun applyBarStyle(chart: BarChart, labels: List<String>) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setFitBars(true)
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(false)
        chart.isDoubleTapToZoomEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            setDrawGridLines(true)
            gridColor = Color.parseColor(COLOR_GRID)
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
            textColor = Color.parseColor(COLOR_TEXT)
            textSize = 10f
            axisMinimum = 0f
            granularity = 1f
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisLineColor = Color.parseColor(COLOR_GRID)
            textColor = Color.parseColor(COLOR_TEXT)
            textSize = 10f
            granularity = 1f
            valueFormatter = IndexAxisValueFormatter(labels)
        }
    }

    private fun applyLineStyle(chart: LineChart, labels: List<String>) {
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setDrawGridBackground(false)
        chart.setPinchZoom(false)
        chart.isDoubleTapToZoomEnabled = false
        chart.setDrawBorders(false)
        chart.axisRight.isEnabled = false
        chart.axisLeft.apply {
            isEnabled = false
            setDrawLabels(false)
            setDrawGridLines(false)
            setDrawAxisLine(false)
        }
        chart.xAxis.apply {
            isEnabled = false
            setDrawLabels(false)
            setDrawGridLines(false)
            setDrawAxisLine(false)
        }
    }
}
