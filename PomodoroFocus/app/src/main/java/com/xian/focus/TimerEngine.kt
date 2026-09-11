package com.xian.focus

import com.xian.focus.data.FocusRepository
import com.xian.focus.data.PomodoroRecord
import com.xian.focus.data.TimerDurations
import com.xian.focus.data.TimerSession
import com.xian.focus.data.TimerSettingsPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimerEngine @Inject constructor(
    private val repository: FocusRepository,
    private val settingsPreferences: TimerSettingsPreferences
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var durations: TimerDurations = settingsPreferences.getDurations()

    private val _timerState = MutableStateFlow(
        TimerState(remainingSeconds = durations.focusSeconds)
    )
    val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

    private val _focusCompletedEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val focusCompletedEvents: SharedFlow<Int> = _focusCompletedEvents.asSharedFlow()

    private val _lockMode = MutableStateFlow(false)
    val lockMode: StateFlow<Boolean> = _lockMode.asStateFlow()

    private var timerJob: Job? = null
    private var focusStartedAt: Long = 0L

    init {
        restoreSession()
    }

    fun currentDurations(): TimerDurations = durations

    fun setLockMode(enabled: Boolean) {
        _lockMode.value = enabled
    }

    fun updateDurations(durations: TimerDurations): TimerDurations {
        this.durations = settingsPreferences.saveDurations(durations)
        if (_timerState.value.status == TimerStatus.IDLE) {
            _timerState.update {
                it.copy(remainingSeconds = this.durations.focusSeconds)
            }
        }
        return this.durations
    }

    fun startTimer(taskId: Int?) {
        timerJob?.cancel()
        focusStartedAt = System.currentTimeMillis()
        _timerState.value = TimerState(
            status = TimerStatus.FOCUSING,
            remainingSeconds = durations.focusSeconds,
            currentTaskId = taskId,
            completedPomodorosInSession = _timerState.value.completedPomodorosInSession
        )
        persistState()
        runCountdown()
    }

    fun pauseTimer() {
        val state = _timerState.value
        if (state.status == TimerStatus.IDLE || state.status == TimerStatus.PAUSED) return
        timerJob?.cancel()
        timerJob = null
        _timerState.update {
            it.copy(status = TimerStatus.PAUSED, pausedFromStatus = state.status)
        }
        persistState()
    }

    fun resumeTimer() {
        val state = _timerState.value
        if (state.status != TimerStatus.PAUSED || state.remainingSeconds <= 0) return
        if (timerJob?.isActive == true) return
        _timerState.update {
            it.copy(
                status = state.pausedFromStatus ?: TimerStatus.FOCUSING,
                pausedFromStatus = null
            )
        }
        persistState()
        runCountdown()
    }

    fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        focusStartedAt = 0L
        _timerState.value = TimerState(remainingSeconds = durations.focusSeconds)
        settingsPreferences.clearSession()
    }

    private fun runCountdown() {
        timerJob = scope.launch {
            while (isActive && _timerState.value.remainingSeconds > 0) {
                delay(1_000L)
                val remaining = _timerState.value.remainingSeconds - 1
                _timerState.update { it.copy(remainingSeconds = remaining) }
                persistState()
                if (remaining == 0) {
                    onPhaseFinished()
                    return@launch
                }
            }
        }
    }

    private suspend fun onPhaseFinished() {
        val state = _timerState.value
        if (state.status == TimerStatus.FOCUSING) {
            val completed = state.completedPomodorosInSession + 1
            val taskId = state.currentTaskId
            if (taskId != null) {
                repository.getTaskById(taskId)?.let { task ->
                    // 重复任务模板不被番茄钟直接标记完成（否则会让模板在每一天都显示完成）；
                    // 当天完成状态由手动勾选生成快照来管理，番茄钟记录仍照常计入统计
                    val isRepeatingTemplate = task.repeatRule != TaskViewModel.REPEAT_NONE && task.templateId == 0
                    if (!isRepeatingTemplate) {
                        val count = task.completedPomodoros + 1
                        repository.updateTask(
                            task.copy(
                                completedPomodoros = count,
                                isCompleted = task.isCompleted || count >= task.estimatedPomodoros
                            )
                        )
                    }
                }
            }
            repository.insertRecord(
                PomodoroRecord(
                    taskId = taskId,
                    startTime = focusStartedAt,
                    endTime = System.currentTimeMillis(),
                    type = "focus",
                    isFinished = true
                )
            )
            _focusCompletedEvents.tryEmit(completed)
            val longBreak = completed % POMODOROS_BEFORE_LONG_BREAK == 0
            _timerState.value = state.copy(
                status = if (longBreak) TimerStatus.LONG_BREAK else TimerStatus.SHORT_BREAK,
                remainingSeconds = if (longBreak) {
                    durations.longBreakSeconds
                } else {
                    durations.shortBreakSeconds
                },
                completedPomodorosInSession = completed,
                pausedFromStatus = null
            )
            focusStartedAt = 0L
            persistState()
            runCountdown()
        } else {
            timerJob = null
            _timerState.value = TimerState(
                status = TimerStatus.IDLE,
                remainingSeconds = durations.focusSeconds,
                completedPomodorosInSession = state.completedPomodorosInSession
            )
            settingsPreferences.clearSession()
        }
    }

    private fun persistState() {
        val state = _timerState.value
        if (state.status == TimerStatus.IDLE) {
            settingsPreferences.clearSession()
            return
        }
        settingsPreferences.saveSession(
            TimerSession(
                status = state.status.name,
                pausedFromStatus = state.pausedFromStatus?.name,
                remainingSeconds = state.remainingSeconds,
                taskId = state.currentTaskId,
                completedPomodoros = state.completedPomodorosInSession,
                focusStartedAt = focusStartedAt
            )
        )
    }

    private fun restoreSession() {
        val session = settingsPreferences.getSession() ?: return
        val status = runCatching { TimerStatus.valueOf(session.status) }.getOrNull()
            ?: return
        if (session.remainingSeconds <= 0 || status == TimerStatus.IDLE) {
            settingsPreferences.clearSession()
            return
        }
        focusStartedAt = session.focusStartedAt
        _timerState.value = TimerState(
            status = status,
            pausedFromStatus = session.pausedFromStatus?.let { runCatching { TimerStatus.valueOf(it) }.getOrNull() },
            remainingSeconds = session.remainingSeconds,
            currentTaskId = session.taskId,
            completedPomodorosInSession = session.completedPomodoros
        )
        if (status != TimerStatus.PAUSED) runCountdown()
    }

    private companion object {
        const val POMODOROS_BEFORE_LONG_BREAK = 4
    }
}
