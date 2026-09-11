package com.xian.focus

import androidx.lifecycle.ViewModel
import com.xian.focus.data.TimerDurations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TimerViewModel @Inject constructor(
    private val engine: TimerEngine
) : ViewModel() {

    val timerState: StateFlow<TimerState> = engine.timerState
    val focusCompletedEvents: SharedFlow<Int> = engine.focusCompletedEvents
    val lockMode: StateFlow<Boolean> = engine.lockMode

    fun startTimer(taskId: Int?) = engine.startTimer(taskId)

    fun pauseTimer() = engine.pauseTimer()

    fun resumeTimer() = engine.resumeTimer()

    fun resetTimer() = engine.resetTimer()

    fun currentDurations(): TimerDurations = engine.currentDurations()

    fun updateDurations(durations: TimerDurations): TimerDurations =
        engine.updateDurations(durations)

    fun setLockMode(enabled: Boolean) = engine.setLockMode(enabled)
}
