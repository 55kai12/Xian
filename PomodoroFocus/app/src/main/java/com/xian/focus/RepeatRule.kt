package com.xian.focus

import java.util.Calendar

/**
 * 重复任务规则「这一天适用吗」的唯一判定。
 *
 * 任务清单（TasksFragment.buildOccurrencesForDay）和统计口径（FocusRepository）都要问同一个
 * 问题。两份实现一旦漂移，就会出现「列表里根本看不见这条完成记录、趋势线却给它计了一笔」——
 * 用户看到的现象就是「任务删了，趋势线还顶着」。
 */
object RepeatRule {

    fun covers(rule: String, start: Long, day: Long): Boolean {
        val startCal = Calendar.getInstance().apply { timeInMillis = start }
        val dayCal = Calendar.getInstance().apply { timeInMillis = day }
        return when (rule) {
            TaskViewModel.REPEAT_DAILY -> true
            TaskViewModel.REPEAT_WEEKLY ->
                startCal.get(Calendar.DAY_OF_WEEK) == dayCal.get(Calendar.DAY_OF_WEEK)
            TaskViewModel.REPEAT_MONTHLY ->
                startCal.get(Calendar.DAY_OF_MONTH) == dayCal.get(Calendar.DAY_OF_MONTH)
            else -> false
        }
    }
}
