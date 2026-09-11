package com.xian.focus

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
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

    /** 从当前主题取色，跟随主题与昼夜切换 */
    private fun accent(view: View) =
        view.context.themedColor(R.attr.colorBrandAccent, R.color.theme_qinglv_accent)
    private fun grid(view: View) = ContextCompat.getColor(view.context, R.color.divider)
    private fun label(view: View) = ContextCompat.getColor(view.context, R.color.text_dark_brown)

    fun setBarData(chart: BarChart, data: List<DailyCount>) {
        applyBarStyle(chart, data.map { it.dayLabel })
        val entries = data.mapIndexed { index, item ->
            BarEntry(index.toFloat(), item.count.toFloat())
        }
        val set = BarDataSet(entries, "").apply {
            color = accent(chart)
            valueTextColor = label(chart)
            valueTextSize = 11f
            setDrawValues(true)
            barShadowColor = ContextCompat.getColor(chart.context, R.color.card_surface)
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
            setCircleColor(Color.WHITE)
            circleRadius = 4f
            setDrawCircleHole(false)
            valueTextColor = Color.WHITE
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
            gridColor = grid(chart)
            setDrawAxisLine(true)
            axisLineColor = grid(chart)
            textColor = label(chart)
            textSize = 10f
            axisMinimum = 0f
            granularity = 1f
        }
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisLineColor = grid(chart)
            textColor = label(chart)
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
