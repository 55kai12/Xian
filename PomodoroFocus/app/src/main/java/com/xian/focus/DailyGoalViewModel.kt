package com.xian.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xian.focus.data.DailyGoalPreferences
import com.xian.focus.data.PomodoroRecordDao
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DailyGoalState(val completed: Int = 0, val goal: Int = 8) {
    val progress: Int get() = ((completed * 100) / goal.coerceAtLeast(1)).coerceIn(0, 100)
}

@HiltViewModel
class DailyGoalViewModel @Inject constructor(
    private val recordDao: PomodoroRecordDao,
    private val preferences: DailyGoalPreferences
) : ViewModel() {
    private val _state = MutableStateFlow(DailyGoalState(goal = preferences.getDailyGoal()))
    val state: StateFlow<DailyGoalState> = _state.asStateFlow()

    init { refresh() }

    fun setDailyGoal(goal: Int) {
        preferences.setDailyGoal(goal)
        _state.value = _state.value.copy(goal = preferences.getDailyGoal())
    }

    fun refresh() = viewModelScope.launch {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        val end = start + 24 * 60 * 60 * 1000L
        _state.value = _state.value.copy(completed = recordDao.getCompletedFocusCountBetween(start, end))
    }
}
