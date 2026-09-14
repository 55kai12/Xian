package com.xian.focus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.DailyCount
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.FocusStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: FocusRepository
) : ViewModel() {
    private val _stats = MutableStateFlow<FocusStats?>(null)
    val stats: StateFlow<FocusStats?> = _stats.asStateFlow()

    private val _weeklyCounts = MutableStateFlow<List<DailyCount>>(emptyList())
    val weeklyCounts: StateFlow<List<DailyCount>> = _weeklyCounts.asStateFlow()
    private val _weeklyWeekStart = MutableStateFlow<Long?>(null)
    val weeklyWeekStart: StateFlow<Long?> = _weeklyWeekStart.asStateFlow()

    suspend fun loadStats(): FocusStats {
        val stats = repository.getFocusStats()
        _stats.value = stats
        return stats
    }

    suspend fun loadWeeklyCounts(): List<DailyCount> {
        val counts = repository.getLastSevenDaysCounts(TaskSkipStore.all(context))
        _weeklyWeekStart.value = null
        _weeklyCounts.value = counts
        return counts
    }

    suspend fun loadWeeklyCountsForWeek(weekStart: Long): List<DailyCount> {
        val counts = repository.getDailyCountsBetween(
            weekStart,
            weekStart + 7L * 24L * 60L * 60L * 1000L,
            TaskSkipStore.all(context)
        )
        _weeklyWeekStart.value = weekStart
        _weeklyCounts.value = counts
        return counts
    }

    fun refresh() = viewModelScope.launch {
        _stats.value = repository.getFocusStats()
        // 别把 _weeklyWeekStart 清成 null：番茄钟页刷新时会调这里，一旦清掉，
        // 任务页趋势线收集器里「这周数据是不是当前显示这周」的判定就永远为假，
        // 曲线会一直挂着旧数据（表现是「翻到第二周后曲线不跟着任务动」）。
        val week = _weeklyWeekStart.value
        _weeklyCounts.value = if (week == null) {
            repository.getLastSevenDaysCounts(TaskSkipStore.all(context))
        } else {
            repository.getDailyCountsBetween(
                week,
                week + 7L * 24L * 60L * 60L * 1000L,
                TaskSkipStore.all(context)
            )
        }
    }
}
