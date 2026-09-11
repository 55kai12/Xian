package com.xian.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.DailyCount
import com.xian.focus.data.FocusRepository
import com.xian.focus.data.FocusStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
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
        val counts = repository.getLastSevenDaysCounts()
        _weeklyWeekStart.value = null
        _weeklyCounts.value = counts
        return counts
    }

    suspend fun loadWeeklyCountsForWeek(weekStart: Long): List<DailyCount> {
        val counts = repository.getDailyCountsBetween(weekStart, weekStart + 7L * 24L * 60L * 60L * 1000L)
        _weeklyWeekStart.value = weekStart
        _weeklyCounts.value = counts
        return counts
    }

    fun refresh() = viewModelScope.launch {
        _stats.value = repository.getFocusStats()
        _weeklyWeekStart.value = null
        _weeklyCounts.value = repository.getLastSevenDaysCounts()
    }
}
