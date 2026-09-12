package com.xian.focus

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent

class FocusLockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /** 当前前台应用（系统组件除外），供应用限额计时用。 */
    private var foregroundPackage: String? = null
    private var lastTickAt = 0L

    private val limitTicker = object : Runnable {
        override fun run() {
            tickAppLimit(applicationContext)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastTickAt = 0L
        handler.removeCallbacks(limitTicker)
        handler.post(limitTicker)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(limitTicker)
        foregroundPackage = null
        AppLimitOverlayController.hide(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(limitTicker)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        val context = applicationContext

        // 系统组件（输入法、状态栏、来电界面）不算「在用某个应用」，
        // 保留上一个真实前台应用 —— 否则一打字限额计时就断了。
        if (!SystemAppAllowlist.isEssential(context, packageName)) {
            foregroundPackage = packageName
        }

        if (LockMachineController.isActive(context)) {
            if (LockMachineController.isAllowed(context, packageName)) {
                if (LockMachineOverlayController.isShowing()) {
                    LockMachineOverlayController.hide(context)
                }
            } else {
                LockMachineOverlayController.show(context)
            }
            return
        } else if (LockMachineOverlayController.isShowing()) {
            LockMachineOverlayController.hide(context)
        }

        // 番茄钟锁机状态改由持久化数据推导，进程被杀后重新拉起依然生效
        if (!LockController.isLockActive(context)) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
            return
        }
        val isSelf = packageName == context.packageName
        // 系统必需组件（来电界面、输入法、状态栏）必须放行，
        // 否则锁机期间接不了电话，白名单应用里也无法输入文字。
        val isSystemEssential = SystemAppAllowlist.isEssential(context, packageName)
        if (isSelf || isSystemEssential) {
            if (LockOverlayController.isShowing()) {
                LockOverlayController.hide(context)
            }
        } else {
            LockOverlayController.show(context)
        }
    }

    /**
     * 每秒把这段时间算到当前前台应用头上，累计超限就盖一层锁。
     *
     * 息屏时不计时 —— 否则手机揣兜里放一晚，第二天额度莫名其妙就满了。
     */
    private fun tickAppLimit(context: Context) {
        val now = System.currentTimeMillis()
        // 卡顿/休眠后的补偿上限：一次 tick 最多只认 MAX_TICK_SECONDS 秒
        val deltaSeconds = if (lastTickAt == 0L) 0L
        else ((now - lastTickAt) / 1000L).coerceIn(0L, MAX_TICK_SECONDS)
        lastTickAt = now

        val interactive = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isInteractive == true
        val packageName = foregroundPackage
        if (!interactive || packageName == null) return

        if (AppLimitStore.isLocked(context, packageName)) {
            // 锁机覆盖层优先级更高，不跟它抢同一块屏
            if (!LockMachineController.isActive(context) && !LockOverlayController.isShowing()) {
                AppLimitOverlayController.show(context, packageName)
            }
            return
        }
        if (AppLimitOverlayController.isShowing()) {
            AppLimitOverlayController.hide(context)
        }
        // 锁机期间本来也玩不了，不记账
        if (!LockMachineController.isActive(context)) {
            AppLimitStore.addSeconds(context, packageName, deltaSeconds)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TICK_MILLIS = 1_000L
        private const val MAX_TICK_SECONDS = 5L
    }
}
