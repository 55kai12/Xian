package com.xian.focus

import com.xian.focus.data.FocusRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 给拿不到 @Inject 的场景（广播接收器等）开的后门。
 * 名字沿用 LockEntryPoint，但里面装的都是全局单例，不只服务锁机。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LockEntryPoint {
    fun timerEngine(): TimerEngine

    fun focusRepository(): FocusRepository
}
