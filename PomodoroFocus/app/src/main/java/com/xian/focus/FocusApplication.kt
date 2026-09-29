package com.xian.focus

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FocusApplication : Application() {

    /**
     * 系统字体缩放的钳制入口之一。
     *
     * 三套悬浮层都拿 `applicationContext` 去 inflate 布局，而 `applicationContext`
     * 就是本对象 —— 换了这里的 base context，等于把锁机层 / 限额层的字体一起钳住了。
     * （界面那一侧管不到，得在 `MainActivity` 再包一次，理由见 [FontScale]。）
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(FontScale.cap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 冷启动时补挂体检闹钟：重复闹钟会被 ROM 清掉，这里重新排上（已存在则不动）。
        LockHealthMonitor.schedule(this)
    }
}
