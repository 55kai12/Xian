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

    private val _lockMode = MutableStateFlow(settingsPreferences.isLockEnabled())
    val lockMode: StateFlow<Boolean> = _lockMode.asStateFlow()

    private var timerJob: Job? = null
    private var focusStartedAt: Long = 0L

    /**
     * 当前阶段（专注 / 短休 / 长休）应当结束的墙钟时间戳，0 表示当前没有在计时的阶段。
     * 剩余秒数一律由它推算，避免"每秒减一"在息屏 / Doze 下被拉长。
     */
    private var phaseEndAt: Long = 0L

    init {
        restoreSession()
    }

    fun currentDurations(): TimerDurations = durations

    fun setLockMode(enabled: Boolean) {
        _lockMode.value = enabled
        // 必须落盘：旧实现只改内存标志位，进程被系统回收后锁机就静默失效了
        settingsPreferences.saveLockEnabled(enabled)
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
        schedulePhaseEnd(durations.focusSeconds)
        persistState()
        runCountdown()
    }

    fun pauseTimer() {
        val state = _timerState.value
        if (state.status == TimerStatus.IDLE || state.status == TimerStatus.PAUSED) return
        timerJob?.cancel()
        timerJob = null
        // 暂停即冻结：丢掉结束时间戳，恢复时按剩余秒数重新推算
        phaseEndAt = 0L
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
        // 先重排结束时刻再落盘，避免把 phaseEndAt = 0 写进 session
        runCountdown()
        persistState()
    }

    fun resetTimer() {
        timerJob?.cancel()
        timerJob = null
        focusStartedAt = 0L
        phaseEndAt = 0L
        _timerState.value = TimerState(remainingSeconds = durations.focusSeconds)
        settingsPreferences.clearSession()
    }

    /** 记录当前阶段应当结束的墙钟时刻。 */
    private fun schedulePhaseEnd(remainingSeconds: Int) {
        phaseEndAt = System.currentTimeMillis() + remainingSeconds * 1_000L
    }

    private fun runCountdown() {
        timerJob?.cancel()
        val startState = _timerState.value
        if (startState.remainingSeconds <= 0) return
        schedulePhaseEnd(startState.remainingSeconds)
        timerJob = scope.launch {
            while (isActive) {
                val millisLeft = phaseEndAt - System.currentTimeMillis()
                if (millisLeft <= 0L) {
                    _timerState.update { it.copy(remainingSeconds = 0) }
                    persistState()
                    onPhaseFinished()
                    return@launch
                }
                // 以墙钟为准反推剩余秒数，而不是"每秒减一"再累加：
                // 息屏 / Doze 会把 delay 拉长，逐秒递减会让 25 分钟的番茄钟实际跑成
                // 半小时以上，统计里记录的时长也和用户体感对不上。
                val remaining = ((millisLeft + 999L) / 1_000L).toInt()
                if (remaining != _timerState.value.remainingSeconds) {
                    _timerState.update { it.copy(remainingSeconds = remaining) }
                    persistState()
                }
                // 睡到下一个整秒边界：UI 每秒刷新一次，且不会引入额外漂移
                delay(millisLeft % 1_000L + TICK_GUARD_MILLIS)
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
            schedulePhaseEnd(_timerState.value.remainingSeconds)
            persistState()
            runCountdown()
        } else {
            timerJob = null
            phaseEndAt = 0L
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
                focusStartedAt = focusStartedAt,
                phaseEndAt = phaseEndAt
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
        var remaining = session.remainingSeconds
        if (status != TimerStatus.PAUSED && session.phaseEndAt > 0L) {
            // 进程不在的时候时间照样在走，必须按墙钟重算剩余，
            // 否则重新拉起后计时会被"倒回"到被杀之前的秒数。
            val millisLeft = session.phaseEndAt - System.currentTimeMillis()
            if (millisLeft <= 0L) {
                // 这一段在进程缺席时就已经走完了。不补记番茄钟（不凭空造数据），
                // 直接回到空闲；同时避免残留的过期 session 让锁机永远解不开。
                phaseEndAt = 0L
                settingsPreferences.clearSession()
                _timerState.value = TimerState(remainingSeconds = durations.focusSeconds)
                return
            }
            remaining = ((millisLeft + 999L) / 1_000L).toInt()
        }
        focusStartedAt = session.focusStartedAt
        _timerState.value = TimerState(
            status = status,
            pausedFromStatus = session.pausedFromStatus?.let { runCatching { TimerStatus.valueOf(it) }.getOrNull() },
            remainingSeconds = remaining,
            currentTaskId = session.taskId,
            completedPomodorosInSession = session.completedPomodoros
        )
        if (status != TimerStatus.PAUSED) runCountdown()
    }

    private companion object {
        const val POMODOROS_BEFORE_LONG_BREAK = 4

        /** 睡到整秒边界时额外多睡几毫秒，避免因取整反复空转。 */
        const val TICK_GUARD_MILLIS = 5L
    }
}
