package com.xian.focus

enum class TimerStatus {
    IDLE,
    FOCUSING,
    SHORT_BREAK,
    LONG_BREAK,
    PAUSED
}

data class TimerState(
    val status: TimerStatus = TimerStatus.IDLE,
    val remainingSeconds: Int = DEFAULT_FOCUS_SECONDS,
    val currentTaskId: Int? = null,
    val completedPomodorosInSession: Int = 0,
    val pausedFromStatus: TimerStatus? = null
) {
    companion object {
        const val DEFAULT_FOCUS_SECONDS = 25 * 60
    }
}
