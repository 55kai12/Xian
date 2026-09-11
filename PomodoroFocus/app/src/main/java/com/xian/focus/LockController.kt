package com.xian.focus

object LockController {
    @Volatile
    var lockEnabled: Boolean = false

    @Volatile
    var timerRunning: Boolean = false

    val isLockActive: Boolean
        get() = lockEnabled && timerRunning

    fun update(lockEnabled: Boolean, timerRunning: Boolean) {
        this.lockEnabled = lockEnabled
        this.timerRunning = timerRunning
    }
}
