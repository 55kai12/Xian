package com.xian.focus

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockEntryPoint {
    fun timerEngine(): TimerEngine
}
