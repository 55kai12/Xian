package com.xian.focus

import android.content.Context
import android.widget.TextView
import com.haibin.calendarview.WeekBar

class XianWeekBar(context: Context) : WeekBar(context) {

    /** 复用已有的 weekday_* 资源，不再写死一份中文。 */
    private val labels = listOf(
        R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed, R.string.weekday_thu,
        R.string.weekday_fri, R.string.weekday_sat, R.string.weekday_sun
    ).map { context.getString(it) }

    override fun onWeekStartChange(weekStart: Int) {
        super.onWeekStartChange(weekStart)
        post {
            labels.forEachIndexed { index, label ->
                (getChildAt(index) as? TextView)?.text = label
            }
        }
    }
}
