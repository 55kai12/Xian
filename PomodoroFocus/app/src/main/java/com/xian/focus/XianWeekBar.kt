package com.xian.focus

import android.content.Context
import android.widget.TextView
import com.haibin.calendarview.WeekBar

class XianWeekBar(context: Context) : WeekBar(context) {

    private val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    override fun onWeekStartChange(weekStart: Int) {
        super.onWeekStartChange(weekStart)
        post {
            labels.forEachIndexed { index, label ->
                (getChildAt(index) as? TextView)?.text = label
            }
        }
    }
}
