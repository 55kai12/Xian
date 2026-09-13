package com.xian.focus

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FocusApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 冷启动时补挂体检闹钟：重复闹钟会被 ROM 清掉，这里重新排上（已存在则不动）。
        LockHealthMonitor.schedule(this)
    }
}
